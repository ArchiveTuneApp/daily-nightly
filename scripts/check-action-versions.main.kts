#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("com.google.code.gson:gson:2.14.0")
@file:DependsOn("org.yaml:snakeyaml:2.6")

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
import java.util.regex.Pattern
import kotlin.system.exitProcess

// --- Data Models ---

data class ActionInfo(
    val owner: String,
    val repo: String,
    val currentVersion: String,
    val latestVersion: String?,
    val latestVersionUrl: String?,
    val latestPublishedAt: String?,
    val isUpToDate: Boolean
)

data class ActionUpdateResult(
    val totalActionsChecked: Int,
    val upToDate: Int,
    val updatesAvailable: Int,
    val actions: List<ActionInfo>
)

// --- Global Instances ---

val gson = Gson()
val client = HttpClient.newHttpClient()

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

/**
 * Fetch the latest release version for a GitHub Action.
 * Returns Pair of (version, publishedAt) or null if no releases found.
 */
fun fetchLatestActionVersion(owner: String, repo: String): Pair<String, String?>? {
    // First, try to get the latest release
    val releaseUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
    try {
        val json = fetch(releaseUrl)
        val data = gson.fromJson(json, JsonObject::class.java)
        
        if (data.has("tag_name") && !data.get("tag_name").isJsonNull) {
            val tagName = data.get("tag_name").asString
            val publishedAt = if (data.has("published_at") && !data.get("published_at").isJsonNull) {
                data.get("published_at").asString
            } else {
                null
            }
            return Pair(tagName.removePrefix("v"), publishedAt)
        }
    } catch (e: Exception) {
        // No latest release, continue to try tags
    }
    
    // Fallback: Try to get the latest tag
    val tagsUrl = "https://api.github.com/repos/$owner/$repo/tags?per_page=1"
    try {
        val json = fetch(tagsUrl)
        val data = gson.fromJson(json, JsonArray::class.java)
        
        if (data.size() > 0) {
            val firstTag = data[0].asJsonObject
            if (firstTag.has("name") && !firstTag.get("name").isJsonNull) {
                val tagName = firstTag.get("name").asString
                return Pair(tagName.removePrefix("v"), null)
            }
        }
    } catch (e: Exception) {
        // Continue to default branch commit check
    }
    
    // Fallback: Check default branch commit
    val branchUrl = "https://api.github.com/repos/$owner/$repo/branches/main"
    try {
        val json = fetch(branchUrl)
        val data = gson.fromJson(json, JsonObject::class.java)
        
        if (data.has("commit") && !data.get("commit").isJsonNull) {
            val commit = data.getAsJsonObject("commit")
            if (commit.has("sha") && !commit.get("sha").isJsonNull) {
                val sha = commit.get("sha").asString
                return Pair(sha.take(7), null)
            }
        }
    } catch (e: Exception) {
        // Try main branch
        try {
            val branchUrl2 = "https://api.github.com/repos/$owner/$repo/branches/master"
            val json2 = fetch(branchUrl2)
            val data2 = gson.fromJson(json2, JsonObject::class.java)
            
            if (data2.has("commit") && !data2.get("commit").isJsonNull) {
                val commit = data2.getAsJsonObject("commit")
                if (commit.has("sha") && !commit.get("sha").isJsonNull) {
                    val sha = commit.get("sha").asString
                    return Pair(sha.take(7), null)
                }
            }
        } catch (e2: Exception) {
            // Give up
        }
    }
    
    return null
}

/**
 * Parse a version string to a comparable list of integers.
 * Handles versions like "v4", "4.0.0", "v1.2.3", "4.0.0-alpha" etc.
 */
fun parseVersion(version: String): List<Int> {
    val cleanVersion = version.removePrefix("v").lowercase()
    val parts = cleanVersion.split(Regex("[^0-9.]+")).filter { it.isNotEmpty() }
    return parts.flatMap { part ->
        if (part.contains(".")) {
            part.split(".").mapNotNull { it.toIntOrNull() }
        } else {
            listOf(part.toIntOrNull() ?: 0)
        }
    }.take(10) // Max 10 version parts
}

/**
 * Compare two versions. Returns true if v1 < v2.
 */
fun isVersionLessThan(v1: String, v2: String): Boolean {
    if (v1.isEmpty() || v2.isEmpty()) return false
    
    val parts1 = parseVersion(v1)
    val parts2 = parseVersion(v2)
    
    val maxLen = maxOf(parts1.size, parts2.size)
    
    for (i in 0 until maxLen) {
        val num1 = if (i < parts1.size) parts1[i] else 0
        val num2 = if (i < parts2.size) parts2[i] else 0
        
        if (num1 < num2) return true
        if (num1 > num2) return false
    }
    
    return false
}

/**
 * Extract all action references from a workflow file content.
 * Matches patterns like: uses: owner/action@version
 */
fun extractActionsFromContent(content: String): List<Pair<String, String>> {
    val actions = mutableListOf<Pair<String, String>>()
    
    // Match uses: owner/action@version or uses: owner/action/version@version
    val pattern = Pattern.compile("uses:\\s*([a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+)(?:/[a-zA-Z0-9_-]+)?@([a-zA-Z0-9._-]+)")
    val matcher = pattern.matcher(content)
    
    while (matcher.find()) {
        val actionRef = matcher.group(1)
        val version = matcher.group(2)
        
        // Skip composite actions and local paths
        if (!actionRef.contains(".") && !actionRef.startsWith("./") && !actionRef.startsWith("../")) {
            actions.add(Pair(actionRef, version))
        }
    }
    
    return actions
}

/**
 * Get all workflow files from .github/workflows/
 */
fun getWorkflowFiles(): List<File> {
    val workflowsDir = File(".github/workflows")
    if (!workflowsDir.exists() || !workflowsDir.isDirectory) {
        throw RuntimeException(".github/workflows directory not found")
    }
    
    return workflowsDir.listFiles { file ->
        file.isFile && (file.name.endsWith(".yml") || file.name.endsWith(".yaml"))
    }?.toList() ?: emptyList()
}

/**
 * Format the update report
 */
fun formatUpdateReport(result: ActionUpdateResult): String {
    val sb = StringBuilder()
    sb.appendLine("=".repeat(80))
    sb.appendLine("GitHub Actions Version Update Report")
    sb.appendLine("Generated: ${java.time.ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))}")
    sb.appendLine("=".repeat(80))
    sb.appendLine()
    
    sb.appendLine("Summary:")
    sb.appendLine("  - Total Actions Checked: ${result.totalActionsChecked}")
    sb.appendLine("  - Up to Date: ${result.upToDate}")
    sb.appendLine("  - Updates Available: ${result.updatesAvailable}")
    sb.appendLine()
    
    if (result.updatesAvailable > 0) {
        sb.appendLine("=".repeat(80))
        sb.appendLine("Updates Available:")
        sb.appendLine("=".repeat(80))
        sb.appendLine()
        
        for (action in result.actions.filter { !it.isUpToDate }) {
            sb.appendLine("Repository: ${action.owner}/${action.repo}")
            sb.appendLine("  Current Version:  ${action.currentVersion}")
            sb.appendLine("  Latest Version:   ${action.latestVersion ?: "Unknown"}")
            sb.appendLine("  Latest URL:       ${action.latestVersionUrl ?: "N/A"}")
            if (action.latestPublishedAt != null) {
                sb.appendLine("  Published:        ${action.latestPublishedAt}")
            }
            sb.appendLine("  Update Type:      ${getUpdateType(action.currentVersion, action.latestVersion ?: "")}")
            sb.appendLine()
        }
    }
    
    if (result.upToDate > 0) {
        sb.appendLine("=".repeat(80))
        sb.appendLine("Up to Date:")
        sb.appendLine("=".repeat(80))
        sb.appendLine()
        
        for (action in result.actions.filter { it.isUpToDate }) {
            sb.appendLine("${action.owner}/${action.repo}@${action.currentVersion}")
        }
        sb.appendLine()
    }
    
    sb.appendLine("=".repeat(80))
    sb.appendLine("All Actions Found:")
    sb.appendLine("=".repeat(80))
    sb.appendLine()
    
    // Group by owner for better readability
    val grouped = result.actions.groupBy { it.owner }
    for ((owner, actions) in grouped.toList().sortedBy { (key, _) -> key }) {
        sb.appendLine("$owner:")
        for (action in actions.toList().sortedBy { it.repo }) {
            val status = if (action.isUpToDate) "✓" else "↻"
            sb.appendLine("  $status ${action.repo}@${action.currentVersion} -> ${action.latestVersion ?: "?"}")
        }
    }
    
    return sb.toString()
}

/**
 * Determine the type of update (major, minor, patch)
 */
fun getUpdateType(current: String, latest: String): String {
    if (current.isEmpty() || latest.isEmpty()) return "Unknown"
    
    val currentParts = parseVersion(current)
    val latestParts = parseVersion(latest)
    
    if (currentParts.size >= 2 && latestParts.size >= 2) {
        if (latestParts[0] > currentParts[0]) return "Major Update ⚠️"
        if (latestParts.size > 1 && latestParts[1] > currentParts[1]) return "Minor Update"
        if (latestParts.size > 2 && latestParts[2] > currentParts[2]) return "Patch Update"
    }
    
    return "Update Available"
}

/**
 * Main function to check all action versions
 */
fun main() {
    println("GitHub Actions Version Checker")
    println("=".repeat(50))
    println()
    
    val allActions = mutableMapOf<String, MutableList<Pair<String, String>>>() // action -> list of (file, version)
    val processedActions = mutableSetOf<String>() // action refs already processed
    
    try {
        // Step 1: Extract actions from all workflow files
        println("Step 1: Scanning workflow files...")
        val workflowFiles = getWorkflowFiles()
        println("Found ${workflowFiles.size} workflow file(s)")
        
        for (file in workflowFiles) {
            println("  - ${file.name}")
            val content = file.readText()
            val actions = extractActionsFromContent(content)
            
            for ((actionRef, version) in actions) {
                if (!allActions.containsKey(actionRef)) {
                    allActions[actionRef] = mutableListOf()
                }
                allActions[actionRef]?.add(Pair(file.name, version))
            }
        }
        
        println()
        println("Found ${allActions.size} unique action(s)")
        println()
        
        // Step 2: Check each action for updates
        println("Step 2: Checking for updates...")
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneId.of("UTC"))
        
        val actionResults = mutableListOf<ActionInfo>()
        var checked = 0
        val total = allActions.size
        
        for ((actionRef, usages) in allActions) {
            checked++
            print("\rChecking action $checked/$total: $actionRef...")
            
            // Skip if already processed (duplicate actions)
            if (processedActions.contains(actionRef)) continue
            processedActions.add(actionRef)
            
            // Get the first version found (they should all be the same)
            val currentVersion = usages.first().second
            
            // Parse owner and repo from action ref
            val parts = actionRef.split("/")
            if (parts.size != 2) {
                println("\n  Warning: Invalid action reference format: $actionRef")
                continue
            }
            
            val owner = parts[0]
            val repo = parts[1]
            
            // Fetch latest version
            val latestInfo = fetchLatestActionVersion(owner, repo)
            
            val latestVersion = latestInfo?.first
            val latestPublishedAt = latestInfo?.second?.let { dateFormatter.format(Instant.parse(it)) }
            
            val isUpToDate = if (latestVersion != null) {
                !isVersionLessThan(latestVersion, currentVersion)
            } else {
                true // Can't determine, assume up to date
            }
            val latestVersionUrl = if (latestVersion != null) {
                "https://github.com/$owner/$repo/releases/tag/v$latestVersion"
            } else {
                null
            }
            
            actionResults.add(
                ActionInfo(
                    owner = owner,
                    repo = repo,
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    latestVersionUrl = latestVersionUrl,
                    latestPublishedAt = latestPublishedAt,
                    isUpToDate = isUpToDate
                )
            )
        }
        
        println() // New line after progress
        println()
        
        // Step 3: Generate report
        println("Step 3: Generating report...")
        
        val upToDateCount = actionResults.count { it.isUpToDate }
        val updatesAvailableCount = actionResults.size - upToDateCount
        
        val result = ActionUpdateResult(
            totalActionsChecked = actionResults.size,
            upToDate = upToDateCount,
            updatesAvailable = updatesAvailableCount,
            actions = actionResults
        )
        
        val report = formatUpdateReport(result)
        
        // Output to console
        println(report)
        
        // Write to file
        val reportFile = File("action-versions-report.md")
        reportFile.writeText(report)
        println()
        println("Report saved to: ${reportFile.absolutePath}")
        
        // Output for GitHub Actions
        println()
        println("GitHub Actions Output:")
        println("::set-output name=UPDATES_AVAILABLE::${updatesAvailableCount}")
        println("::set-output name=TOTAL_CHECKED::${actionResults.size}")
        
        if (updatesAvailableCount > 0) {
            val outdatedActions = actionResults.filter { !it.isUpToDate }
                .joinToString("\n") { "${it.owner}/${it.repo}: ${it.currentVersion} -> ${it.latestVersion}" }
            println("::set-output name=OUTDATED_ACTIONS::$outdatedActions")
        }
        
        // Exit with error code if updates are available
        if (updatesAvailableCount > 0) {
            println()
            println("⚠️  ${updatesAvailableCount} action(s) have updates available!")
            // Don't exit with error - this is informational
            // exitProcess(1)
        } else {
            println()
            println("✅ All actions are up to date!")
        }
        
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

// Run the main function
main()
