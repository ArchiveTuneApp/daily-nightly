#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("com.google.code.gson:gson:2.14.0")

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.system.exitProcess

// --- Global Instances ---
val gson = Gson()
val client = HttpClient.newHttpClient()
val logOutput = StringBuilder() // To capture log output for changelog

// --- Helper Functions ---

fun log(message: String) {
    println(message)
    logOutput.append(message).append("\n")
}

fun fetch(url: String): String {
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
        .GET()
        .build()

    while (true) {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val code = response.statusCode()

        if (code in 200..299) {
            return response.body()
        }

        if (code == 403 || code == 429) {
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
            val resetEpoch = response.headers().firstValue("X-RateLimit-Reset").orElse(null)

            val waitSeconds = when {
                retryAfter != null -> retryAfter.toLong()
                resetEpoch != null -> resetEpoch.toLong() - Instant.now().epochSecond
                else -> null
            }

            val baseDelay = (waitSeconds?.coerceAtLeast(1L) ?: 5L).toLong()
            val jitter = Random.nextLong(0, 26L) // up to +25s extra
            val delay = baseDelay + jitter

            log("Rate limited ($code). Retrying in ${delay}s (base: ${baseDelay}s, jitter: +${jitter}s)...")
            Thread.sleep(delay * 1000)
            continue
        }

        throw RuntimeException("Failed to fetch: $url - Status: $code - Body: ${response.body()}")
    }
}

fun fetchCommits(owner: String, repo: String, branch: String, since: String, until: String): JsonArray {
    // We use the commits API with since and until parameters
    val url = "https://api.github.com/repos/$owner/$repo/commits?sha=$branch&since=$since&until=$until"
    log("Fetching commits from: $url")
    val json = fetch(url)
    return gson.fromJson(json, JsonArray::class.java)
}

data class CommitFiles(
    val sha: String,
    val filename: String,
    val status: String,   // added, modified, removed, renamed
    val additions: Int,
    val deletions: Int
)

fun fetchCommitFiles(owner: String, repo: String, sha: String): List<CommitFiles> {
    val url = "https://api.github.com/repos/$owner/$repo/commits/$sha"
    val json = fetch(url)
    val root = gson.fromJson(json, JsonObject::class.java)
    if (!root.has("files")) return emptyList()
    val files = root.getAsJsonArray("files")
    return files.map { file ->
        val obj = file.asJsonObject
        CommitFiles(
            sha = sha,
            filename = obj.get("filename").asString,
            status = obj.get("status").asString,
            additions = obj.get("additions").asInt,
            deletions = obj.get("deletions").asInt
        )
    }
}

fun fetchCommitsSinceSha(owner: String, repo: String, branch: String, sha: String): JsonArray {
    val url = "https://api.github.com/repos/$owner/$repo/compare/$sha...$branch"
    log("Fetching commits from compare API: $url")
    val json = fetch(url)
    val root = gson.fromJson(json, JsonObject::class.java)
    if (root.has("commits")) {
        val commits = root.getAsJsonArray("commits")
        // Compare API returns oldest first; reverse to match commits API convention (newest first)
        val reversed = JsonArray()
        for (i in commits.size() - 1 downTo 0) {
            reversed.add(commits[i])
        }
        return reversed
    }
    throw RuntimeException("No commits field in compare response: $json")
}

fun formatChangelog(commits: JsonArray, logOutput: String, owner: String, repo: String): String {
    val sb = StringBuilder()
    
    // Check if commits is empty
    if (commits.size() == 0) {
        return buildString {
            append("## ✨ Changelog\n")
            append("\n")
            append("No new commits found for this time period.\n")
            append("\n")
            append("## 📃 Debug Output\n")
            append("\n")
            append("```bash\n")
            append(logOutput)
            append("```\n")
        }
    }
    
    // Commits are ordered newest-first (both APIs normalized to this convention)
    val commitList = commits.map { it.asJsonObject }

    // Date and time format
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("UTC")) // Timezone

    val authorCommitCounts = mutableMapOf<String, Int>()
    val changelogEntries = StringBuilder()

    for (commit in commitList) {
        try {
            val sha = commit.get("sha").asString
            val commitDetails = commit.getAsJsonObject("commit")
            
            val fullMessage = commitDetails.get("message").asString
            var message = fullMessage
            if (message.isEmpty()) {
                message = "(no message)"
            } else {
                message = message.split("\n")[0]
            }
            
            // Replace issue number #123 with the link
            val issueRegex = "#(\\d+)".toRegex()
            message = issueRegex.replace(message) { matchResult ->
                val number = matchResult.groupValues[1]
                "[#$number](https://github.com/ArchiveTuneApp/ArchiveTune/issues/$number)"
            }

            // Get primary author (preferably GitHub login, fallback to Git username)
            val primaryAuthor = try {
                if (commit.has("author") && !commit.get("author").isJsonNull) {
                    val authorObj = commit.getAsJsonObject("author")
                    if (authorObj.has("login") && !authorObj.get("login").isJsonNull) {
                        authorObj.get("login").asString
                    } else {
                        getCommitAuthorName(commitDetails)
                    }
                } else {
                    getCommitAuthorName(commitDetails)
                }
            } catch (e: Exception) {
                "Unknown"
            }

            // Parse co-authors from Co-authored-by trailers
            val coAuthors = parseCoAuthors(fullMessage)

            // Combine primary + co-authors, deduplicated
            val allAuthors = (listOf(primaryAuthor) + coAuthors).distinct()

            // Update commit counts for ALL authors
            for (author in allAuthors) {
                authorCommitCounts[author] = (authorCommitCounts[author] ?: 0) + 1
            }

            // Format date and time
            val date = try {
                val commitAuthor = commitDetails.getAsJsonObject("author")
                val dateStr = commitAuthor.get("date").asString
                dateFormatter.format(Instant.parse(dateStr))
            } catch (e: Exception) {
                "Unknown Date"
            }

            // Fetch changed files for this commit
            val files = try {
                fetchCommitFiles(owner, repo, sha)
            } catch (e: Exception) {
                log("Warning: Could not fetch files for ${sha.take(7)}: ${e.message}")
                emptyList()
            }

            // Create log line with all authors
            val authorsStr = allAuthors.joinToString(", ") { "@$it" }
            changelogEntries.append("- [`${sha.take(7)}`](https://github.com/$owner/$repo/commit/$sha) - **\"$message\"** by ($authorsStr)\n")

            // Append changed files summary
            if (files.isNotEmpty()) {
                for (file in files) {
                    val changeType = when (file.status) {
                        "added" -> "A"
                        "modified" -> "M"
                        "removed" -> "D"
                        "renamed" -> "R"
                        else -> "M"
                    }
                    val stats = "+${file.additions}/-${file.deletions}"
                    changelogEntries.append("  - `${file.filename}` ($changeType, $stats)\n")
                }
            }
        } catch (e: Exception) {
            log("Warning: Error processing commit: ${e.message}")
            continue
        }
    }
    
    // Add MVP section if there are authors
    if (authorCommitCounts.isNotEmpty()) {
        val sortedAuthors = authorCommitCounts.entries.sortedByDescending { it.value }
        val topAuthor = sortedAuthors.first()
        
        sb.append("## 🏆 MVP Committer\n")
        sb.append("\n")
        sb.append("Congratulations to **@${topAuthor.key}** for contributing **${topAuthor.value}** commit(s) in this release! 🎉\n")
        
        if (sortedAuthors.size > 1) {
            sb.append("\n")
            sb.append("### 📊 Contribution Leaderboard\n")
            sb.append("\n")
            for ((index, entry) in sortedAuthors.withIndex()) {
                val medal = when(index) {
                    0 -> "🥇"
                    1 -> "🥈"
                    2 -> "🥉"
                    else -> "👤"
                }
                sb.append("$medal **@${entry.key}**: ${entry.value} commit(s)\n")
            }
        }
        sb.append("\n")
    }

    sb.append("## ✨ Changelog\n")
    sb.append("\n")
    sb.append(changelogEntries.toString())
    
    return sb.toString()
}

fun getCommitAuthorName(commitDetails: JsonObject): String {
    if (!commitDetails.has("author") || commitDetails.get("author").isJsonNull) {
        return "Unknown"
    }
    val authorObj = commitDetails.getAsJsonObject("author")
    if (!authorObj.has("name") || authorObj.get("name").isJsonNull) {
        return "Unknown"
    }
    return authorObj.get("name").asString
}

fun parseCoAuthors(fullMessage: String): List<String> {
    val coAuthorRegex = "Co-authored-by:\\s*(.+)\\s*<([^>]+)>".toRegex()
    return coAuthorRegex.findAll(fullMessage).map { match ->
        val name = match.groupValues[1].trim()
        val email = match.groupValues[2].trim()
        // Extract GitHub username from noreply email (e.g. 12345+username@users.noreply.github.com)
        val noreplyMatch = "\\d+\\+(.+)@users\\.noreply\\.github\\.com".toRegex().matchEntire(email)
        if (noreplyMatch != null) {
            noreplyMatch.groupValues[1]
        } else {
            // Fallback: use the name part, sanitized
            name.replace("\\s+".toRegex(), "")
        }
    }.distinct().toList()
}



// --- Main Execution ---

fun main() {
    try {
        val repoPath = System.getenv("REPO_PATH") ?: "ArchiveTuneApp/ArchiveTune"
        val branch = System.getenv("BRANCH") ?: "dev"
        
        if (!repoPath.contains("/")) {
            System.err.println("Error: Invalid repository path format: $repoPath (expected: owner/repo)")
            exitProcess(1)
        }
        val (owner, repoName) = repoPath.split("/")

        val sinceSha = System.getenv("SINCE_SHA") ?: ""

        // Calculate UTC 0 dates: yesterday 00:00 UTC → now (fallback)
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        val sinceDate = now.minusDays(1).truncatedTo(ChronoUnit.DAYS) // Yesterday 00:00 UTC
        val untilDate = now // Current datetime UTC

        val isoFormatter = DateTimeFormatter.ISO_INSTANT
        val since = isoFormatter.format(sinceDate.toInstant())
        val until = isoFormatter.format(untilDate.toInstant())

        log("Generating changelog for $repoPath ($branch)")

        // Get the commit list
        log("Fetching commits from GitHub API...")
        val commits = try {
            if (sinceSha.isNotBlank()) {
                log("Using SINCE_SHA: $sinceSha")
                fetchCommitsSinceSha(owner, repoName, branch, sinceSha)
            } else {
                log("No SINCE_SHA provided. Using date range (UTC 0): $since to $until")
                fetchCommits(owner, repoName, branch, since, until)
            }
        } catch (e: Exception) {
            System.err.println("Error: Failed to fetch commits from GitHub - ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
        
        log("Found ${commits.size()} commit(s)")
        
        log("Formatting changelog...")
        val changelog = formatChangelog(commits, logOutput.toString(), owner, repoName)
        
        log("Writing changelog to file...")
        File("changelog.md").writeText(changelog)
        log("✅ Changelog generated successfully: changelog.md")

    } catch (e: Exception) {
        System.err.println("Error: Unexpected error generating changelog - ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

main()
