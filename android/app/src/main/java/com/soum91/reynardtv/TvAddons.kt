package com.soum91.reynardtv

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** GeckoView extension management; uses Mozilla's signature and permission checks. */
class TvAddons(private val context: Context, runtime: GeckoRuntime) {
    private val controller = runtime.webExtensionController

    init {
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val response = GeckoResult<WebExtension.PermissionPromptResponse>()
                val details = (permissions.toList() + origins.toList() + dataCollectionPermissions.toList()).distinct()
                AlertDialog.Builder(context)
                    .setTitle("Install ${extension.metaData?.name ?: "extension"}?")
                    .setMessage("Requested permissions:\n${if (details.isEmpty()) "None" else details.joinToString("\n")}")
                    .setPositiveButton("Install") { _, _ -> response.complete(WebExtension.PermissionPromptResponse(true, false, false)) }
                    .setNegativeButton("Cancel") { _, _ -> response.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                    .setOnCancelListener { response.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                    .show()
                return response
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> {
                val result = GeckoResult<AllowOrDeny>()
                val all = (newPermissions.toList() + newOrigins.toList() + newDataCollectionPermissions.toList()).distinct()
                AlertDialog.Builder(context).setTitle("Update ${extension.metaData?.name} permissions?")
                    .setMessage(all.joinToString("\n"))
                    .setPositiveButton("Allow") { _, _ -> result.complete(AllowOrDeny.ALLOW) }
                    .setNegativeButton("Deny") { _, _ -> result.complete(AllowOrDeny.DENY) }
                    .setOnCancelListener { result.complete(AllowOrDeny.DENY) }
                    .show()
                return result
            }
        })
    }

    fun showManager() {
        controller.list().accept({ installed ->
            val items = installed ?: emptyList()
            val labels = items.map { addon ->
                "${addon.metaData?.name ?: addon.id}  (${if (addon.metaData?.enabled == true) "Enabled" else "Disabled"})"
            }.toMutableList()
            labels.add("+ Install signed XPI from URL")
            AlertDialog.Builder(context).setTitle("Firefox Add-ons")
                .setItems(labels.toTypedArray()) { _, index ->
                    if (index == items.size) promptInstall() else showExtension(items[index])
                }
                .setNegativeButton("Close", null).show()
        }, { e -> toast("Could not list add-ons: ${e?.message}") })
    }

    private fun promptInstall() {
        val entry = EditText(context).apply { setSingleLine(); hint = "https://.../addon.xpi" }
        AlertDialog.Builder(context).setTitle("Install Firefox add-on").setView(entry)
            .setMessage("Use a direct URL to a signed Mozilla-compatible .xpi package.")
            .setPositiveButton("Install") { _, _ ->
                val url = entry.text.toString().trim()
                if (!url.startsWith("https://")) {
                    toast("Enter a secure HTTPS download URL")
                    return@setPositiveButton
                }
                controller.install(url).accept({ addon ->
                    toast("Installed ${addon?.metaData?.name ?: addon?.id ?: "extension"}")
                }, { error -> toast("Install failed: ${error?.message}") })
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showExtension(extension: WebExtension) {
        val enabled = extension.metaData?.enabled == true
        val actions = arrayOf(if (enabled) "Disable" else "Enable", "Check for updates", "Uninstall")
        AlertDialog.Builder(context).setTitle(extension.metaData?.name ?: extension.id)
            .setMessage("Version ${extension.metaData?.version ?: "?"}\n${extension.metaData?.description.orEmpty()}")
            .setItems(actions) { _, choice ->
                when (choice) {
                    0 -> {
                        val result = if (enabled) controller.disable(extension, WebExtensionController.EnableSource.USER)
                            else controller.enable(extension, WebExtensionController.EnableSource.USER)
                        result.accept({ toast(if (enabled) "Disabled" else "Enabled") }, { toast("Failed: ${it?.message}") })
                    }
                    1 -> controller.update(extension).accept({
                        toast(if (it == null) "Already up to date" else "Updated to ${it.metaData?.version}")
                    }, { toast("Update failed: ${it?.message}") })
                    2 -> AlertDialog.Builder(context).setTitle("Uninstall ${extension.metaData?.name}?")
                        .setPositiveButton("Uninstall") { _, _ ->
                            controller.uninstall(extension).accept({ toast("Uninstalled") }, { toast("Failed: ${it?.message}") })
                        }.setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
