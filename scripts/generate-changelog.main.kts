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
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

// --- Data Models ---
data class CommitInfo(
    val sha: String,
    val message: String,
    val repository: String,
    val branch: String
)

data class History(val commit: CommitInfo)

// --- Global Instances ---
val gson = Gson()
val client = HttpClient.newHttpClient()
val logOutput = StringBuilder() // To capture log output for changelog

// --- Helper Functions ---

fun log(message: String) {
    println(message)
    logOutput.append(message).append("\n")
}

// --- Helper Functions ---

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

fun fetchLatestSha(owner: String, repo: String, branch: String): String {
    val url = "https://api.github.com/repos/$owner/$repo/branches/$branch"
    val json = fetch(url)
    val data = gson.fromJson(json, JsonObject::class.java)
    
    if (!data.has("commit") || data.get("commit").isJsonNull) {
        throw RuntimeException("No 'commit' field in branch API response for $owner/$repo/$branch")
    }
    
    val commitObj = data.getAsJsonObject("commit")
    if (!commitObj.has("sha") || commitObj.get("sha").isJsonNull) {
        throw RuntimeException("No 'sha' field in commit object for $owner/$repo/$branch")
    }
    
    return commitObj.get("sha").asString
}

fun fetchCommits(owner: String, repo: String, sinceSha: String, untilSha: String): JsonArray {
    val url = "https://api.github.com/repos/$owner/$repo/compare/$sinceSha...$untilSha"
    val json = fetch(url)
    val data = gson.fromJson(json, JsonObject::class.java)
    
    if (!data.has("commits")) {
        log("Warning: No 'commits' field in API response")
        return JsonArray()
    }
    
    val commits = data.get("commits")
    if (commits.isJsonNull) {
        log("Warning: 'commits' field is null in API response")
        return JsonArray()
    }
    
    return commits.asJsonArray
}

fun formatChangelog(commits: JsonArray, logOutput: String): String {
    val sb = StringBuilder()
    
    // Check if commits is empty
    if (commits.size() == 0) {
        // Generate timestamp
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd - HH:mm:ss")
            .withZone(ZoneId.of("UTC"))
        val timestamp = formatter.format(Instant.now())
        
        return buildString {
            append("Release **ArchiveTune Nightly** artifact, generated on **$timestamp (UTC +0)**\n")
            append("\n")
            append("## ✨ Changelog\n")
            append("\n")
            append("No new commits found... Wait the hold up? Huh? No commit found but why this release still create?\n")
            append("\n")
            append("## 📃 Debug Output\n")
            append("\n")
            append("```bash\n")
            append(logOutput)
            append("```\n")
            append("\n")
            append("> [!NOTE]  \n")
            append("> If this version broken something, you can create this [issue](https://github.com/sang765/ArchiveTune-Nightly/issues/new?template=report-version-broken.yml). Thanks you.")
        }
    }
    
    // Generate timestamp
    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd - HH:mm:ss")
        .withZone(ZoneId.of("UTC"))
    val timestamp = formatter.format(Instant.now())
    
    sb.append("Release **ArchiveTune Nightly** artifact, generated on **$timestamp (UTC +0)**\n")
    sb.append("\n")
    sb.append("## ✨ Changelog\n")
    sb.append("\n")
    
    // The GitHub API returns the commit order from oldest to newest in the compare view.
    // We reverse the order to display the newest commit at the top.
    val commitList = commits.map { it.asJsonObject }.reversed()

    // Date and time format
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("UTC")) // Timezone

    for (commit in commitList) {
        try {
            // Safely get SHA
            if (!commit.has("sha") || commit.get("sha").isJsonNull) {
                log("Warning: Commit without SHA found, skipping")
                continue
            }
            val sha = commit.get("sha").asString
            
            // Safely get commit details
            if (!commit.has("commit") || commit.get("commit").isJsonNull) {
                log("Warning: Commit without details found (SHA: $sha), skipping")
                continue
            }
            val commitDetails = commit.getAsJsonObject("commit")
            
            // Get the first line of the commit message with null check
            if (!commitDetails.has("message") || commitDetails.get("message").isJsonNull) {
                log("Warning: Commit without message (SHA: $sha), skipping")
                continue
            }
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
                "[#$number](https://github.com/koiverse/ArchiveTune/issues/$number)"
            }

            // Get author information (preferably GitHub login, fallback to Git username)
            val author = try {
                if (commit.has("author") && !commit.get("author").isJsonNull) {
                    val authorObj = commit.getAsJsonObject("author")
                    if (authorObj.has("login") && !authorObj.get("login").isJsonNull) {
                        authorObj.get("login").asString
                    } else {
                        // Fallback to commit details author
                        getCommitAuthorName(commitDetails)
                    }
                } else {
                    getCommitAuthorName(commitDetails)
                }
            } catch (e: Exception) {
                log("Warning: Error getting author for commit $sha: ${e.message}")
                "Unknown"
            }

            // Format date and time
            val date = try {
                if (!commitDetails.has("author") || commitDetails.get("author").isJsonNull) {
                    "Unknown Date"
                } else {
                    val commitAuthor = commitDetails.getAsJsonObject("author")
                    if (!commitAuthor.has("date") || commitAuthor.get("date").isJsonNull) {
                        "Unknown Date"
                    } else {
                        val dateStr = commitAuthor.get("date").asString
                        dateFormatter.format(Instant.parse(dateStr))
                    }
                }
            } catch (e: Exception) {
                log("Warning: Error parsing date for commit $sha: ${e.message}")
                "Unknown Date"
            }

            // Create log line
            sb.append("- `$date`: [`${sha.take(7)}`](https://github.com/koiverse/ArchiveTune/commit/$sha) - **\"$message\"** by (@$author)\n")
        } catch (e: Exception) {
            log("Warning: Error processing commit: ${e.message}")
            e.printStackTrace()
            continue
        }
    }
    
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
        var lastSha: String? = null
        var repoPath: String? = null
        var branch: String? = null

        // 1. Check environment variables first
        if (System.getenv("LAST_SHA") != null) {
            log("Reading commit info from environment variables...")
            lastSha = System.getenv("LAST_SHA")
            repoPath = System.getenv("LAST_REPO") ?: "koiverse/ArchiveTune"
            branch = System.getenv("LAST_BRANCH") ?: "dev"
        } else {
            // 2. If there is no environment, read the history/commit.json file.
            val historyFile = File("history/commit.json")
            
            // Check the GITHUB_ACTIONS environment variable to ensure the logic matches the old file.
            if (System.getenv("GITHUB_ACTIONS") != null) {
                log("Reading commit info from history/commit.json...")
                if (historyFile.exists()) {
                    try {
                        val historyData = historyFile.readText()
                        if (historyData.trim().isEmpty()) {
                            System.err.println("Error: history/commit.json is empty")
                            exitProcess(1)
                        }
                        val history = gson.fromJson(historyData, History::class.java)
                        
                        if (history == null || history.commit == null) {
                            System.err.println("Error: Invalid history file structure")
                            exitProcess(1)
                        }
                        
                        lastSha = history.commit.sha
                        repoPath = history.commit.repository
                        branch = history.commit.branch
                        
                        log("Last commit: $lastSha")
                        log("Repository: $repoPath")
                        log("Branch: $branch")
                    } catch (e: Exception) {
                        System.err.println("Error: Failed to parse history/commit.json - ${e.message}")
                        e.printStackTrace()
                        exitProcess(1)
                    }
                } else {
                    System.err.println("Error: history/commit.json file not found")
                    exitProcess(1)
                }
            } else {
                // Fallback for local developers if needed.
                log("Not running in GitHub Actions, skipping changelog generation")
                return
            }
        }

        // Unwrap nullable values
        if (lastSha == null || repoPath == null || branch == null) {
            System.err.println("Error: Missing required information (SHA/Repo/Branch)")
            exitProcess(1)
        }

        // Validate repository path format
        if (!repoPath.contains("/")) {
            System.err.println("Error: Invalid repository path format: $repoPath (expected: owner/repo)")
            exitProcess(1)
        }
        
        val (owner, repoName) = repoPath.split("/")
        
        // Get the latest SHA from remote
        log("Fetching latest commit from GitHub API...")
        val latestSha = try {
            fetchLatestSha(owner, repoName, branch)
        } catch (e: Exception) {
            System.err.println("Error: Failed to fetch latest SHA from GitHub - ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }

        log("Comparing from $lastSha to $latestSha")

        // If there are no changes
        if (lastSha == latestSha) {
            log("No new commits found.")
            val changelog = formatChangelog(JsonArray(), logOutput.toString())
            File("changelog.md").writeText(changelog)
            return
        }

        // Get the commit list and create a changelog.
        log("Fetching commits from GitHub API...")
        
        // Determine the correct order for comparison
        // The GitHub compare API uses 'base...head' format where:
        // - If base is behind head: status = "ahead", ahead_by = number of commits
        // - If base is ahead of head: status = "behind", behind_by = number of commits
        // This handles cases where the branch may have been force-pushed
        val commits = try {
            // Check which direction to compare
            val compareUrl = "https://api.github.com/repos/$owner/$repoName/compare/$lastSha...$latestSha"
            val testJson = fetch(compareUrl)
            val testData = gson.fromJson(testJson, JsonObject::class.java)
            val status = if (testData.has("status")) testData.get("status").asString else "unknown"
            val totalCommits = if (testData.has("total_commits")) testData.get("total_commits").asInt else 0
            
            if (status == "ahead" || (status == "diverged" && totalCommits > 0)) {
                // lastSha is older than latestSha - use normal order
                fetchCommits(owner, repoName, lastSha, latestSha)
            } else if (status == "behind" || totalCommits == 0) {
                // lastSha is newer than latestSha - reverse the order (branch was force-pushed)
                fetchCommits(owner, repoName, latestSha, lastSha)
            } else {
                // Unknown status, try normal order first
                fetchCommits(owner, repoName, lastSha, latestSha)
            }
        } catch (e: Exception) {
            System.err.println("Error: Failed to fetch commits from GitHub - ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
        
        log("Found ${commits.size()} commit(s)")
        
        log("Formatting changelog...")
        val changelog = formatChangelog(commits, logOutput.toString())
        
        log("Writing changelog to file...")
        try {
            File("changelog.md").writeText(changelog)
            log("✅ Changelog generated successfully: changelog.md")
        } catch (e: Exception) {
            System.err.println("Error: Failed to write changelog.md - ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }

    } catch (e: Exception) {
        System.err.println("Error: Unexpected error generating changelog - ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

// Run the main function
main()
