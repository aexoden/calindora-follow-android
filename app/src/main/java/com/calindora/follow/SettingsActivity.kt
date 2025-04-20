package com.calindora.follow

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )

            WindowInsetsCompat.CONSUMED
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

            val credentialStatusPref = findPreference<Preference>("preference_credential_status")
            val resetCredentialBlockPref = findPreference<Preference>("preference_reset_credential_block")

            updateCredentialBlockStatus(sharedPreferences, credentialStatusPref, resetCredentialBlockPref)

            resetCredentialBlockPref?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Reset Authentication Block")
                    .setMessage("This will clear the authentication failure counter and re-enable submissions. Make sure you've fixed any credential issues before proceeding.")
                    .setPositiveButton("Reset") { _, _ ->
                        SubmissionWorker.resetUnauthorizedCounter(requireContext())
                        Toast.makeText(requireContext(), "Authentication block reset. Retrying submissions.", Toast.LENGTH_LONG).show()

                        val workRequest = OneTimeWorkRequestBuilder<SubmissionWorker>()
                            .build()
                        WorkManager.getInstance(requireContext())
                            .enqueueUniqueWork(
                                "settings_reset_retry",
                                ExistingWorkPolicy.REPLACE,
                                workRequest
                            )

                        updateCredentialBlockStatus(sharedPreferences, credentialStatusPref, resetCredentialBlockPref)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

                true
            }

            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener { prefs, key ->
                if (key == SubmissionWorker.PREF_SUBMISSIONS_BLOCKED ||
                    key == SubmissionWorker.PREF_CONSECUTIVE_UNAUTHORIZED) {
                    updateCredentialBlockStatus(prefs, credentialStatusPref, resetCredentialBlockPref)
                }
            }
        }

        private fun updateCredentialBlockStatus(
            prefs: SharedPreferences,
            statusPref: Preference?,
            resetPref: Preference?
        ) {
            val blocked = prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
            val count = prefs.getInt(SubmissionWorker.PREF_CONSECUTIVE_UNAUTHORIZED, 0)

            if (blocked) {
                statusPref?.summary = getString(R.string.preference_credential_status_blocked)
                resetPref?.isVisible = true
            } else {
                if (count > 0) {
                    statusPref?.summary = "Authentication warnings: $count consecutive failures"
                } else {
                    statusPref?.summary = getString(R.string.preference_credential_status_ok)
                }

                resetPref?.isVisible = false
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefListener)
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
        }

        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == SubmissionWorker.PREF_SUBMISSIONS_BLOCKED ||
                key == SubmissionWorker.PREF_CONSECUTIVE_UNAUTHORIZED) {
                val statusPref = findPreference<Preference>("preference_credential_status")
                val resetPref = findPreference<Preference>("preference_reset_credential_block")
                updateCredentialBlockStatus(prefs, statusPref, resetPref)
            }
        }
    }
}
