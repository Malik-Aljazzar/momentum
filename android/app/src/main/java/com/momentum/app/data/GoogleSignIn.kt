package com.momentum.app.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Sign in with Google (server/google.py): the Credential Manager's account picker gives an ID token for the
 * server's web client id (sent in /api/meta); the server checks it and logs in the Google account's own player.
 */
object GoogleSignIn {
    /** The ID token, or null when the player closed the picker. Other failures throw. */
    suspend fun idToken(context: Context, clientId: String): String? {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(context).getCredential(context, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        }
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
