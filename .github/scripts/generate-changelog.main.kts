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

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        throw RuntimeException("Failed to fetch: $url - Status: ${response.statusCode()} - Body: ${response.body()}")
    }
    return response.body()
}

fun fetchCommits(owner: String, repo: String, branch: String, since: String, until: String): JsonArray {
    // We use the commits API with since and until parameters
    val url = "https://api.github.com/repos/$owner/$repo/commits?sha=$branch&since=$since&until=$until"
    log("Fetching commits from: $url")
    val json = fetch(url)
    return gson.fromJson(json, JsonArray::class.java)
}

fun formatChangelog(commits: JsonArray, logOutput: String): String {
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
    
    // GitHub API returns commits in reverse chronological order (newest first)
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
            
            var message = commitDetails.get("message").asString
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

            // Get author information (preferably GitHub login, fallback to Git username)
            val author = try {
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

            // Update commit counts
            authorCommitCounts[author] = (authorCommitCounts[author] ?: 0) + 1

            // Format date and time
            val date = try {
                val commitAuthor = commitDetails.getAsJsonObject("author")
                val dateStr = commitAuthor.get("date").asString
                dateFormatter.format(Instant.parse(dateStr))
            } catch (e: Exception) {
                "Unknown Date"
            }

            // Create log line
            changelogEntries.append("- `$date`: [`${sha.take(7)}`](https://github.com/ArchiveTuneApp/ArchiveTune/commit/$sha) - **\"$message\"** by (@$author)\n")
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

        // Calculate UTC 0 dates: yesterday 00:00 UTC → now
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        val sinceDate = now.minusDays(1).truncatedTo(ChronoUnit.DAYS) // Yesterday 00:00 UTC
        val untilDate = now // Current datetime UTC
        
        val isoFormatter = DateTimeFormatter.ISO_INSTANT
        val since = isoFormatter.format(sinceDate.toInstant())
        val until = isoFormatter.format(untilDate.toInstant())

        log("Generating changelog for $repoPath ($branch)")
        log("Time period (UTC 0): $since to $until")

        // Get the commit list
        log("Fetching commits from GitHub API...")
        val commits = try {
            fetchCommits(owner, repoName, branch, since, until)
        } catch (e: Exception) {
            System.err.println("Error: Failed to fetch commits from GitHub - ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
        
        log("Found ${commits.size()} commit(s)")
        
        log("Formatting changelog...")
        val changelog = formatChangelog(commits, logOutput.toString())
        
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
