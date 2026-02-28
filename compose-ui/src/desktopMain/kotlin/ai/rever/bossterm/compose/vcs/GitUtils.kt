package ai.rever.bossterm.compose.vcs

import ai.rever.bossterm.compose.util.ShellEscaper
import ai.rever.bossterm.compose.util.PerformanceCounters
import ai.rever.bossterm.compose.util.SubprocessHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared utility functions for Git and GitHub CLI operations.
 * Used by both context menu (VersionControlMenuProvider) and window menu (MenuActions).
 */
object GitUtils {
    private data class CacheEntry<T>(val value: T, val checkedAtMs: Long)

    private const val STATUS_CACHE_TTL_MS = 30_000L
    private val gitRepoCache = ConcurrentHashMap<String, CacheEntry<Boolean>>()
    private val ghRepoConfiguredCache = ConcurrentHashMap<String, CacheEntry<Boolean>>()
    private val currentBranchCache = ConcurrentHashMap<String, CacheEntry<String?>>()
    private val localBranchesCache = ConcurrentHashMap<String, CacheEntry<List<String>>>()

    private fun cacheKey(cwd: String?): String = cwd ?: "<null>"

    private fun <T> readCached(
        cache: ConcurrentHashMap<String, CacheEntry<T>>,
        key: String
    ): T? {
        val now = System.currentTimeMillis()
        val cached = cache[key] ?: return null
        return if (now - cached.checkedAtMs <= STATUS_CACHE_TTL_MS) {
            PerformanceCounters.increment("git_status_cache_hit")
            cached.value
        } else {
            null
        }
    }

    private fun <T> writeCached(
        cache: ConcurrentHashMap<String, CacheEntry<T>>,
        key: String,
        value: T
    ): T {
        PerformanceCounters.increment("git_status_cache_write")
        cache[key] = CacheEntry(value = value, checkedAtMs = System.currentTimeMillis())
        return value
    }

    /**
     * Check if a directory is inside a git repository.
     */
    fun isGitRepo(cwd: String?): Boolean {
        if (cwd == null) return false
        val key = cacheKey(cwd)
        readCached(gitRepoCache, key)?.let { return it }
        PerformanceCounters.increment("git_status_cache_miss")
        val result = SubprocessHelper
            .run("git", "-C", cwd, "rev-parse", "--git-dir", timeoutMs = 2_000L)
            .success
        return writeCached(gitRepoCache, key, result)
    }

    /**
     * Check if GitHub CLI has a default repository configured for this directory.
     * Checks the git config for 'remote.origin.gh-resolved' which gh sets when
     * you run 'gh repo set-default'.
     */
    fun isGhRepoConfigured(cwd: String?): Boolean {
        if (cwd == null) return false
        val key = cacheKey(cwd)
        readCached(ghRepoConfiguredCache, key)?.let { return it }
        PerformanceCounters.increment("git_status_cache_miss")
        val commandResult = SubprocessHelper.run(
            "git",
            "-C",
            cwd,
            "config",
            "--get",
            "remote.origin.gh-resolved",
            timeoutMs = 2_000L
        )
        val result = commandResult.success && commandResult.stdout.isNotEmpty()
        return writeCached(ghRepoConfiguredCache, key, result)
    }

    /**
     * Get the current branch name.
     */
    fun getCurrentBranch(cwd: String?): String? {
        if (cwd == null) return null
        val key = cacheKey(cwd)
        readCached(currentBranchCache, key)?.let { return it }
        PerformanceCounters.increment("git_status_cache_miss")
        val commandResult = SubprocessHelper.run(
            "git",
            "-C",
            cwd,
            "rev-parse",
            "--abbrev-ref",
            "HEAD",
            timeoutMs = 2_000L
        )
        val output = commandResult.stdout
        val result = if (commandResult.success && output.isNotEmpty() && output != "HEAD") {
            output
        } else {
            null
        }
        return writeCached(currentBranchCache, key, result)
    }

    /**
     * Get list of local branches.
     */
    fun getLocalBranches(cwd: String?): List<String> {
        if (cwd == null) return emptyList()
        val key = cacheKey(cwd)
        readCached(localBranchesCache, key)?.let { return it }
        PerformanceCounters.increment("git_status_cache_miss")
        val commandResult = SubprocessHelper.run(
            "git",
            "-C",
            cwd,
            "branch",
            "--format=%(refname:short)",
            timeoutMs = 2_000L
        )
        val result = if (commandResult.success) {
            commandResult.stdout.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return writeCached(localBranchesCache, key, result)
    }

    // === Git Commands ===

    /**
     * Build a git command that runs in the specified directory.
     * @param cmd The git subcommand and arguments (e.g., "status", "log --oneline -10")
     * @param cwd The working directory (if null, runs without -C flag)
     * @return The full command string with newline
     */
    fun gitCommand(cmd: String, cwd: String?): String {
        return if (cwd != null) {
            "git -C ${ShellEscaper.escapePosix(cwd)} $cmd\n"
        } else {
            "git $cmd\n"
        }
    }

    /**
     * Build a gh command that runs in the specified directory.
     * @param cmd The gh subcommand and arguments (e.g., "pr list", "issue create")
     * @param cwd The working directory (if null, runs without cd)
     * @return The full command string with newline
     */
    fun ghCommand(cmd: String, cwd: String?): String {
        return if (cwd != null) {
            "cd ${ShellEscaper.escapePosix(cwd)} && gh $cmd\n"
        } else {
            "gh $cmd\n"
        }
    }

    fun cacheStats(): Map<String, Long> {
        return mapOf(
            "git_repo_entries" to gitRepoCache.size.toLong(),
            "gh_repo_entries" to ghRepoConfiguredCache.size.toLong(),
            "branch_entries" to currentBranchCache.size.toLong(),
            "branches_entries" to localBranchesCache.size.toLong(),
            "hits" to PerformanceCounters.get("git_status_cache_hit"),
            "misses" to PerformanceCounters.get("git_status_cache_miss"),
            "writes" to PerformanceCounters.get("git_status_cache_write")
        )
    }

    // === Common Git Command Strings ===

    object Commands {
        const val INIT = "init"
        const val CLONE = "clone "
        const val STATUS = "status"
        const val DIFF = "diff"
        const val LOG = "log --oneline -10"
        const val ADD_ALL = "add ."
        const val ADD_PATCH = "add -p"
        const val RESET = "reset HEAD"
        const val COMMIT = "commit"
        const val COMMIT_AMEND = "commit --amend"
        const val PUSH = "push"
        const val PULL = "pull"
        const val FETCH = "fetch --all"
        const val BRANCH = "branch -a"
        const val CHECKOUT_PREV = "checkout -"
        const val CHECKOUT_NEW = "checkout -b "
        const val STASH = "stash"
        const val STASH_POP = "stash pop"
    }

    object GhCommands {
        const val AUTH_STATUS = "auth status"
        const val AUTH_LOGIN = "auth login"
        const val SET_DEFAULT = "repo set-default"
        const val REPO_CLONE = "repo clone "
        const val PR_LIST = "pr list"
        const val PR_STATUS = "pr status"
        const val PR_CREATE = "pr create"
        const val PR_VIEW_WEB = "pr view --web"
        const val ISSUE_LIST = "issue list"
        const val ISSUE_CREATE = "issue create"
        const val REPO_VIEW_WEB = "repo view --web"
    }
}
