package com.family.locationsender.ui

import android.content.Intent
import android.os.Bundle
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
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: Prefs

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applySaved(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots / recents preview of the password screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        SessionState.lock()

        binding.tvTitle.setText(R.string.lock_title)
        binding.tvHint.setText(R.string.lock_hint)

        binding.btnUnlock.setOnClickListener { tryUnlock() }
        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            tryUnlock(); true
        }

        binding.btnSwitchLang.setOnClickListener {
            val newLang = if (prefs.language == Prefs.LANG_AR) Prefs.LANG_EN else Prefs.LANG_AR
            LocaleHelper.setLanguage(this, newLang)
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        SessionState.lock()
        binding.etPassword.text?.clear()
    }

    private fun tryUnlock() {
        val input = binding.etPassword.text?.toString().orEmpty()
        if (input.isEmpty()) {
            binding.etPassword.error = getString(R.string.required)
            return
        }
        if (prefs.checkPassword(input)) {
            SessionState.unlock()
            val next = if (prefs.firstRunDone) MainActivity::class.java else SetupActivity::class.java
            startActivity(Intent(this, next))
            finish()
        } else {
            Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
            binding.etPassword.text?.clear()
        }
    }
}
