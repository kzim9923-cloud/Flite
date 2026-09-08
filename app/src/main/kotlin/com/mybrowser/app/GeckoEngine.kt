package com.mybrowser.app

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Single GeckoRuntime used by the browser.
 *
 * Extensions are installed from Mozilla Add-ons (AMO) at runtime.
 * No XPI is bundled in app/src/main/assets.
 */
object GeckoEngine {
    private var runtime: GeckoRuntime? = null

    @Synchronized
    fun getRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            runtime = GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .build()
            )
        }
        return runtime!!
    }

    fun installExtension(
        context: Context,
        xpiUrl: String,
        onSuccess: (WebExtension) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val controller = getRuntime(context).webExtensionController
        controller.install(
            xpiUrl,
            WebExtensionController.INSTALLATION_METHOD_MANAGER
        ).accept(
            { extension ->
                if (extension != null) {
                    Log.i("GeckoEngine", "Extension installed: ${extension.id}")
                    onSuccess(extension)
                } else {
                    onError(IllegalStateException("GeckoView returned no extension"))
                }
            },
            { error ->
                Log.e("GeckoEngine", "Extension install failed: $xpiUrl", error)
                onError(error ?: IllegalStateException("Unknown extension install error"))
            }
        )
    }

    fun listExtensions(context: Context, onResult: (List<WebExtension>) -> Unit) {
        getRuntime(context).webExtensionController.list().accept(
            { extensions -> onResult(extensions ?: emptyList()) },
            { error ->
                Log.e("GeckoEngine", "Unable to list extensions", error)
                onResult(emptyList())
            }
        )
    }

    fun uninstallExtension(
        context: Context,
        extension: WebExtension,
        onDone: () -> Unit
    ) {
        getRuntime(context).webExtensionController.uninstall(extension).accept(
            { onDone() },
            { error ->
                Log.e("GeckoEngine", "Unable to uninstall extension", error)
                onDone()
            }
        )
    }
}
