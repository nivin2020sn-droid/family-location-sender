package com.family.locationsender.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.family.locationsender.R

/**
 * Optional Device Admin component. When enabled by the user, removing the
 * app first requires disabling device admin from system settings, which
 * provides a soft layer of protection against casual uninstalls.
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, R.string.device_admin_enabled_toast, Toast.LENGTH_LONG).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, R.string.device_admin_disabled_toast, Toast.LENGTH_LONG).show()
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, AppDeviceAdminReceiver::class.java)
    }
}
