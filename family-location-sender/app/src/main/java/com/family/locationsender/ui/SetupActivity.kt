package com.family.locationsender.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.family.locationsender.R
import com.family.locationsender.data.Prefs
import com.family.locationsender.databinding.ActivitySetupBinding
import com.family.locationsender.util.LocaleHelper
import com.family.locationsender.util.ImageUtils
import com.family.locationsender.util.SessionState
import java.io.ByteArrayOutputStream

/**
 * First-run setup screen. Collects name, family code, API endpoint, optional
 * new password, profile photo and update interval. Saves to encrypted prefs.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: Prefs
    private var profileBase64: String = ""

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applySaved(newBase))
    }

    private val pickGallery = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            val bmp = ImageUtils.decodeOriented(this, uri)
            if (bmp != null) setProfileBitmap(bmp)
            else Toast.makeText(this, R.string.error_load_image, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_load_image, Toast.LENGTH_SHORT).show()
        }
    }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        bmp ?: return@registerForActivityResult
        setProfileBitmap(bmp)
    }

    private val askCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) takePhoto.launch(null)
        else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.get(this)

        // Preload existing values (in case user re-opens setup)
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

        binding.btnPickGallery.setOnClickListener { pickGallery.launch("image/*") }
        binding.btnPickCamera.setOnClickListener {
            askCamera.launch(Manifest.permission.CAMERA)
        }
        binding.btnSave.setOnClickListener { save(intervals) }
    }

    override fun onResume() {
        super.onResume()
        // If session was wiped (background/idle), go back to lock.
        if (!SessionState.authenticated) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
        }
    }

    private fun setProfileBitmap(bmp: Bitmap) {
        val scaled = scaleBitmap(bmp, 512)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        profileBase64 = "data:image/jpeg;base64," +
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        renderProfilePreview()
    }

    private fun renderProfilePreview() {
        if (profileBase64.isBlank()) {
            binding.ivProfile.setImageResource(R.drawable.ic_avatar_placeholder)
            return
        }
        val raw = profileBase64.substringAfter(",", profileBase64)
        try {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) binding.ivProfile.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    private fun scaleBitmap(src: Bitmap, maxSide: Int): Bitmap {
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
        val newPass = binding.etNewPassword.text.toString()

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
        if (newPass.isNotEmpty()) {
            if (newPass.length < 4) {
                binding.etNewPassword.error = getString(R.string.password_too_short); return
            }
            prefs.setPassword(newPass)
        }
        prefs.firstRunDone = true

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
