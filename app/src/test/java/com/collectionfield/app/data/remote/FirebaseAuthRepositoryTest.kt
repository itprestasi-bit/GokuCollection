package com.collectionfield.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseAuthRepositoryTest {
    @Test
    fun employeeCode_isNormalizedForInternalAuthEmail() {
        assertEquals("COL001", FirebaseAuthRepository.normalizeEmployeeCode(" col 001 "))
        assertEquals(
            "col001@collectionfield.app",
            FirebaseAuthRepository.employeeEmail(" col 001 "),
        )
    }
}
