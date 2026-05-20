package com.family.locationsender.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.family.locationsender.R
import com.family.locationsender.data.Prefs
import com.family.locationsender.databinding.ActivitySettingsBinding
import com.family.locationsender.receiver.AppDeviceAdminReceiver
import com.family.locationsender.service.LocationForegroundService
import com.family.locationsender.util.LocaleHelper
import com.family.locationsender.util.SessionState
import java.io.ByteArrayOutputStream

/**
 * Password-protected settings. The user reaches here only after entering the
 * current password on LockActivity. Allows changing all settings + password
 * + Device Admin enrollment.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var profileBase64: String = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applySaved(newBase))
    }

    private val pickGallery = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri).use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return@use
                setProfileBitmap(bmp)
            }
        } catch (_: Exception) {}
    }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? -> bmp?.let { setProfileBitmap(it) } }

    private val askCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) takePhoto.launch(null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.get(this)

        binding.etName.setText(prefs.memberName)
        binding.etFamilyCode.setText(prefs.familyCode)
        binding.etApi.setText(prefs.apiEndpoint)
        profileBase64 = prefs.profileImage
        renderProfilePreview()

        val intervals = listOf(
            getString(R.string.interval_smart) to Prefs.INTERVAL_SMART,
            getString(R.string.interval_1m) to Prefs.INTERVAL_1MIN,
            getString(R.string.interval_3m) to Prefs.INTERVAL_3MIN,
            getString(R.string.interval_5m) to Prefs.INTERVAL_5MIN,
            getString(R.string.interval_15m) to Prefs.INTERVAL_15MIN
        )
        val labels = intervals.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerInterval.adapter = adapter
        val currentIdx = intervals.indexOfFirst { it.second == prefs.updateInterval }.coerceAtLeast(0)
        binding.spinnerInterval.setSelection(currentIdx)

        // Language radio
        when (prefs.language) {
            Prefs.LANG_AR -> binding.rbAr.isChecked = true
            else -> binding.rbEn.isChecked = true
        }

        binding.btnPickGallery.setOnClickListener { pickGallery.launch("image/*") }
        binding.btnPickCamera.setOnClickListener {
            askCamera.launch(android.Manifest.permission.CAMERA)
        }
        binding.btnChangePassword.setOnClickListener { changePassword() }
        binding.btnSave.setOnClickListener { save(intervals) }
        binding.btnEnableAdmin.setOnClickListener { enableDeviceAdmin() }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionState.authenticated) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
        }
    }

    private fun changePassword() {
        val current = binding.etCurrentPassword.text.toString()
        val newPass = binding.etNewPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()

        if (!prefs.checkPassword(current)) {
            Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show(); return
        }
        if (newPass.length < 4) {
            binding.etNewPassword.error = getString(R.string.password_too_short); return
        }
        if (newPass != confirm) {
            binding.etConfirmPassword.error = getString(R.string.passwords_mismatch); return
        }
        prefs.setPassword(newPass)
        binding.etCurrentPassword.text?.clear()
        binding.etNewPassword.text?.clear()
        binding.etConfirmPassword.text?.clear()
        Toast.makeText(this, R.string.password_updated, Toast.LENGTH_SHORT).show()
    }

    private fun enableDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val comp = AppDeviceAdminReceiver.componentName(this)
        if (dpm?.isAdminActive(comp) == true) {
            Toast.makeText(this, R.string.device_admin_already_active, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
            )
        startActivity(intent)
    }

    private fun setProfileBitmap(bmp: Bitmap) {
        val scaled = scale(bmp, 512)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        profileBase64 = "data:image/jpeg;base64," +
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        renderProfilePreview()
    }

    private fun renderProfilePreview() {
        if (profileBase64.isBlank()) {
            binding.ivProfile.setImageResource(R.drawable.ic_avatar_placeholder); return
        }
        val raw = profileBase64.substringAfter(",", profileBase64)
        try {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) binding.ivProfile.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    private fun scale(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxSide && h <= maxSide) return src
        val ratio = w.toFloat() / h.toFloat()
        val (nw, nh) = if (w >= h) maxSide to (maxSide / ratio).toInt()
        else (maxSide * ratio).toInt() to maxSide
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun save(intervals: List<Pair<String, String>>) {
        val name = binding.etName.text.toString().trim()
        val family = binding.etFamilyCode.text.toString().trim()
        val api = binding.etApi.text.toString().trim()

        if (name.isEmpty()) { binding.etName.error = getString(R.string.required); return }
        if (family.isEmpty()) { binding.etFamilyCode.error = getString(R.string.required); return }
        if (api.isEmpty() || !(api.startsWith("http://") || api.startsWith("https://"))) {
            binding.etApi.error = getString(R.string.invalid_url); return
        }

        prefs.memberName = name
        prefs.familyCode = family
        prefs.apiEndpoint = api
        prefs.profileImage = profileBase64
        prefs.updateInterval = intervals[binding.spinnerInterval.selectedItemPosition].second

        val newLang = if (binding.rbAr.isChecked) Prefs.LANG_AR else Prefs.LANG_EN
        val langChanged = newLang != prefs.language
        prefs.language = newLang

        // If tracking is on, restart the service so it picks up the new interval / data
        if (prefs.trackingEnabled) {
            LocationForegroundService.start(this)
        }

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        if (langChanged) {
            // Recreate activity stack with new locale
            startActivity(Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        } else {
            finish()
        }
    }
}
