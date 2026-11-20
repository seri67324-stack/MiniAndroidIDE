package com.miniide.app.git

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Prompt 7 JGit Tabanlı Git Yöneticisi ve Android Keystore (AES-GCM) PAT Kasası.
 * Tüm ağ ve disk işlemleri çağıran tarafından Dispatchers.IO üzerinde yürütülmelidir.
 */
object GitManager {

    enum class FileGitState(val badge: String) {
        MODIFIED("M"),
        ADDED("A"),
        UNTRACKED("U"),
        DELETED("D")
    }

    data class GitRepoStatus(
        val isGitRepo: Boolean,
        val branch: String,
        val staged: List<String>,
        val unstaged: List<String>,
        val untracked: List<String>,
        val fileStates: Map<String, FileGitState>
    )

    data class CommitEntry(
        val shortHash: String,
        val message: String,
        val author: String,
        val dateText: String
    )

    fun isGitRepo(projectDir: File): Boolean = File(projectDir, ".git").isDirectory

    fun init(projectDir: File): String {
        val gitignore = File(projectDir, ".gitignore")
        if (!gitignore.exists()) {
            gitignore.writeText(GitDiffAndIgnore.DEFAULT_ANDROID_GITIGNORE, Charsets.UTF_8)
        }
        Git.init().setDirectory(projectDir).call().use { git ->
            return git.repository.branch ?: "master"
        }
    }

    fun clone(url: String, destDir: File, username: String, token: String): File {
        val cmd = Git.cloneRepository()
            .setURI(url.trim())
            .setDirectory(destDir)
        if (token.isNotBlank()) {
            cmd.setCredentialsProvider(
                UsernamePasswordCredentialsProvider(username.ifBlank { "git" }, token)
            )
        }
        cmd.call().use {}
        return destDir
    }

    fun status(projectDir: File): GitRepoStatus {
        if (!isGitRepo(projectDir)) {
            return GitRepoStatus(false, "-", emptyList(), emptyList(), emptyList(), emptyMap())
        }
        val ignoreFile = File(projectDir, ".gitignore")
        val matcher = if (ignoreFile.isFile) {
            GitDiffAndIgnore.GitIgnoreMatcher(ignoreFile.readText(Charsets.UTF_8))
        } else {
            GitDiffAndIgnore.GitIgnoreMatcher(GitDiffAndIgnore.DEFAULT_ANDROID_GITIGNORE)
        }

        Git.open(projectDir).use { git ->
            val branch = git.repository.branch ?: "HEAD"
            val st = git.status().call()

            val staged = (st.added + st.changed + st.removed).sorted()
            val unstaged = (st.modified + st.missing).filterNot { matcher.isIgnored(it) }.sorted()
            val untracked = st.untracked.filterNot { matcher.isIgnored(it) }.sorted()

            val states = LinkedHashMap<String, FileGitState>()
            for (p in st.added) states[p] = FileGitState.ADDED
            for (p in st.untracked) if (!matcher.isIgnored(p)) states[p] = FileGitState.UNTRACKED
            for (p in st.changed + st.modified) if (!matcher.isIgnored(p)) states[p] = FileGitState.MODIFIED
            for (p in st.removed + st.missing) states[p] = FileGitState.DELETED

            return GitRepoStatus(true, branch, staged, unstaged, untracked, states)
        }
    }

    fun stageFile(projectDir: File, relPath: String) {
        Git.open(projectDir).use { git ->
            val target = File(projectDir, relPath)
            if (target.exists()) {
                git.add().addFilepattern(relPath).call()
            } else {
                git.rm().addFilepattern(relPath).call()
            }
        }
    }

    fun stageAll(projectDir: File) {
        Git.open(projectDir).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
        }
    }

    fun unstageFile(projectDir: File, relPath: String) {
        Git.open(projectDir).use { git ->
            git.reset().addPath(relPath).call()
        }
    }

    fun commit(projectDir: File, message: String, authorName: String = "MiniIDE", authorEmail: String = "ide@local"): String {
        Git.open(projectDir).use { git ->
            val rev = git.commit()
                .setMessage(message.trim())
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .call()
            return rev.name.take(7)
        }
    }

    fun listBranches(projectDir: File): List<String> {
        if (!isGitRepo(projectDir)) return emptyList()
        Git.open(projectDir).use { git ->
            return git.branchList().call().map { it.name.removePrefix("refs/heads/") }
        }
    }

    fun createAndCheckoutBranch(projectDir: File, branchName: String) {
        Git.open(projectDir).use { git ->
            git.checkout().setCreateBranch(true).setName(branchName.trim()).call()
        }
    }

    fun checkoutBranch(projectDir: File, branchName: String) {
        Git.open(projectDir).use { git ->
            git.checkout().setName(branchName.trim()).call()
        }
    }

    fun pull(projectDir: File, username: String, token: String): String {
        Git.open(projectDir).use { git ->
            val cmd = git.pull()
            if (token.isNotBlank()) {
                cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(username.ifBlank { "git" }, token))
            }
            val res = cmd.call()
            return if (res.isSuccessful) "Pull başarılı" else "Pull tamamlandı: ${res.mergeResult?.mergeStatus}"
        }
    }

    fun push(projectDir: File, username: String, token: String): String {
        Git.open(projectDir).use { git ->
            val cmd = git.push()
            if (token.isNotBlank()) {
                cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(username.ifBlank { "git" }, token))
            }
            cmd.call()
            return "Push tamamlandı"
        }
    }

    fun log(projectDir: File, maxCount: Int = 30): List<CommitEntry> {
        if (!isGitRepo(projectDir)) return emptyList()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return try {
            Git.open(projectDir).use { git ->
                git.log().setMaxCount(maxCount).call().map { rev ->
                    CommitEntry(
                        shortHash = rev.name.take(7),
                        message = rev.shortMessage,
                        author = rev.authorIdent?.name ?: "Bilinmeyen",
                        dateText = fmt.format(Date(rev.commitTime.toLong() * 1000L))
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** HEAD commit'indeki dosya içeriğini döndürür (gutter diff ve diff görünümü için). */
    fun getHeadFileContent(projectDir: File, relativePath: String): String? {
        if (!isGitRepo(projectDir)) return null
        val norm = relativePath.replace('\\', '/').trimStart('/')
        return try {
            Git.open(projectDir).use { git ->
                val repo = git.repository
                val headId = repo.resolve(Constants.HEAD) ?: return null
                RevWalk(repo).use { revWalk ->
                    val commit = revWalk.parseCommit(headId)
                    TreeWalk.forPath(repo, norm, commit.tree)?.use { tw ->
                        val loader = repo.open(tw.getObjectId(0))
                        String(loader.bytes, Charsets.UTF_8)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Kişisel Erişim Anahtarını (PAT) Android Keystore AES/GCM anahtarıyla şifreleyerek saklar.
     */
    object TokenVault {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "miniide_git_pat_aes_key"
        private const val PREFS_NAME = "ide_git_vault"
        private const val KEY_USER = "git_user"
        private const val KEY_TOKEN_ENC = "git_pat_enc"
        private const val KEY_TOKEN_IV = "git_pat_iv"

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return keyGen.generateKey()
        }

        fun saveCredentials(context: Context, username: String, token: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            prefs.putString(KEY_USER, username.trim())
            if (token.isEmpty()) {
                prefs.remove(KEY_TOKEN_ENC).remove(KEY_TOKEN_IV)
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val enc = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
                prefs.putString(KEY_TOKEN_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
                prefs.putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            }
            prefs.apply()
        }

        fun loadCredentials(context: Context): Pair<String, String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val user = prefs.getString(KEY_USER, "") ?: ""
            val encB64 = prefs.getString(KEY_TOKEN_ENC, null) ?: return user to ""
            val ivB64 = prefs.getString(KEY_TOKEN_IV, null) ?: return user to ""
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
                val dec = cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP))
                user to String(dec, Charsets.UTF_8)
            } catch (_: Exception) {
                user to ""
            }
        }
    }
}
