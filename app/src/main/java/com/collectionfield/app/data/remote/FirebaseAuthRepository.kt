package com.collectionfield.app.data.remote

import android.content.Context
import android.provider.Settings
import com.collectionfield.app.data.repository.SessionRepository
import com.collectionfield.app.domain.CollectorSession
import com.collectionfield.app.domain.UserRole
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class FirebaseAuthRepository(
    private val context: Context,
    private val sessionRepository: SessionRepository,
    private val cloudDataSource: FirebaseCloudDataSource? = null,
) {
    fun isConfigured(): Boolean = FirebaseBootstrap.isReady(context)

    fun currentSession(): CollectorSession? {
        if (!isConfigured()) return null
        val local = sessionRepository.currentSession() ?: return null
        val firebaseUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        return local.takeIf { it.uid == firebaseUid }
    }

    suspend fun login(employeeCode: String, pin: String): Result<CollectorSession> = runCatching {
        val code = normalizeEmployeeCode(employeeCode)
        require(code.isNotBlank()) { "Employee ID wajib diisi" }
        require(code.matches(Regex("[A-Z0-9_-]{2,32}"))) { "Format Employee ID tidak valid" }
        require(pin.length in 6..12) { "PIN harus 6-12 digit" }
        require(pin.all(Char::isDigit)) { "PIN harus berupa angka" }
        check(isConfigured()) {
            "Firebase belum dikonfigurasi. Tambahkan app/google-services.json terlebih dahulu."
        }

        val auth = FirebaseAuth.getInstance()
        val firestore = FirebaseFirestore.getInstance()
        val authResult = auth.signInWithEmailAndPassword(employeeEmail(code), pin).awaitResult()
        val user = authResult.user ?: error("Firebase Auth tidak mengembalikan user")

        try {
            val profile = firestore.collection("users").document(user.uid).get().awaitResult()
            check(profile.exists()) { "Profil collector belum dibuat di Firestore" }

            val active = profile.getBoolean("active") ?: false
            check(active) { "Akun collector tidak aktif" }

            val profileCode = normalizeEmployeeCode(profile.getString("employee_code").orEmpty())
            check(profileCode == code) { "Employee ID tidak cocok dengan profil akun" }

            val role = runCatching {
                UserRole.valueOf(profile.getString("role").orEmpty().uppercase())
            }.getOrDefault(UserRole.COLLECTOR)

            val session = CollectorSession(
                uid = user.uid,
                employeeCode = code,
                displayName = profile.getString("name").orEmpty().ifBlank { code },
                role = role,
                branchId = profile.getString("branch_id"),
                teamId = profile.getString("team_id"),
            )

            val deviceId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()

            cloudDataSource?.upsertUser(
                uid = session.uid,
                employeeCode = session.employeeCode,
                name = session.displayName,
                role = session.role.name,
                branchId = session.branchId,
                deviceId = deviceId,
            )

            sessionRepository.save(session)
            session
        } catch (error: Throwable) {
            auth.signOut()
            sessionRepository.logout()
            throw error
        }
    }

    fun logout() {
        if (isConfigured()) runCatching { FirebaseAuth.getInstance().signOut() }
        sessionRepository.logout()
    }

    companion object {
        private const val AUTH_DOMAIN = "collectionfield.app"

        fun normalizeEmployeeCode(value: String): String =
            value.trim().uppercase().replace(" ", "")

        fun employeeEmail(employeeCode: String): String =
            "${normalizeEmployeeCode(employeeCode).lowercase()}@$AUTH_DOMAIN"
    }
}
