package com.family.locationsender.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.locationsender.R
import com.family.locationsender.data.Prefs
import com.family.locationsender.databinding.ActivityLockBinding
import com.family.locationsender.util.LocaleHelper
import com.family.locationsender.util.SessionState

/**
 * Password gate. Launcher activity. Routes to:
 *  - SetupActivity if first run
 *  - MainActivity otherwise
 * Locks the session on resume.
 *
 * All initialisation is wrapped in try/catch so that any failure (e.g. encrypted
 * prefs unavailable, theme issue) does NOT cause an immediate process crash.
 * Errors are logged to logcat under the `FLS-LockActivity` tag.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private var prefs: Prefs? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applySaved(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Block screenshots / recents preview of the password screen.
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (t: Throwable) {
            Log.e(TAG, "FLAG_SECURE failed", t)
        }

        try {
            binding = ActivityLockBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to inflate lock layout", t)
            // Last-resort: show a minimal text view instead of crashing.
            val tv = android.widget.TextView(this)
            tv.text = "Family Location Sender — startup error, see logcat"
            setContentView(tv)
            return
        }

        try {
            prefs = Prefs.get(this)
            SessionState.lock()

            binding.tvTitle.setText(R.string.lock_title)
            binding.tvHint.setText(R.string.lock_hint)

            binding.btnUnlock.setOnClickListener { tryUnlock() }
            binding.etPassword.setOnEditorActionListener { _, _, _ ->
                tryUnlock(); true
            }

            binding.btnSwitchLang.setOnClickListener {
                try {
                    val current = prefs?.language ?: Prefs.LANG_EN
                    val newLang = if (current == Prefs.LANG_AR) Prefs.LANG_EN else Prefs.LANG_AR
                    LocaleHelper.setLanguage(this, newLang)
                    recreate()
                } catch (t: Throwable) {
                    Log.e(TAG, "Language switch failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "LockActivity onCreate setup failed", t)
            Toast.makeText(this, "Startup error — see logcat", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            SessionState.lock()
            binding.etPassword.text?.clear()
        } catch (t: Throwable) {
            Log.e(TAG, "onResume failed", t)
        }
    }

    private fun tryUnlock() {
        try {
            val p = prefs ?: run {
                Log.e(TAG, "prefs is null, cannot unlock")
                Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
                return
            }
            val input = binding.etPassword.text?.toString().orEmpty()
            if (input.isEmpty()) {
                binding.etPassword.error = getString(R.string.required)
                return
            }
            if (p.checkPassword(input)) {
                SessionState.unlock()
                val next = if (p.firstRunDone) MainActivity::class.java else SetupActivity::class.java
                startActivity(Intent(this, next))
                finish()
            } else {
                Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
                binding.etPassword.text?.clear()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "tryUnlock failed", t)
            Toast.makeText(this, "Error — see logcat", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "FLS-LockActivity"
    }
}
