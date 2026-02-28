package ai.rever.bossterm.compose

import kotlinx.coroutines.await

/**
 * Web (wasmJs) implementation of PlatformServices.
 * Uses browser APIs with feature detection for graceful degradation.
 */
actual fun getPlatformServices(): PlatformServices = WebPlatformServices

private object WebPlatformServices : PlatformServices {
    override fun getClipboardService(): PlatformServices.ClipboardService = WebClipboardService
    override fun getFileSystemService(): PlatformServices.FileSystemService = WebFileSystemService
    override fun getProcessService(): PlatformServices.ProcessService = WebProcessService
    override fun getPlatformInfo(): PlatformServices.PlatformInfo = WebPlatformInfo
    override fun getBrowserService(): PlatformServices.BrowserService = WebBrowserService
    override fun getNotificationService(): PlatformServices.NotificationService = WebNotificationService
}

private object WebClipboardService : PlatformServices.ClipboardService {
    override suspend fun copyText(text: String): Boolean {
        return try {
            js("navigator.clipboard.writeText(text)")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun pasteText(): String? {
        return try {
            val result: JsString = js("navigator.clipboard.readText()").unsafeCast<JsAny>() as JsString
            result.toString()
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun hasText(): Boolean {
        return pasteText() != null
    }
}

private object WebFileSystemService : PlatformServices.FileSystemService {
    override suspend fun fileExists(path: String): Boolean = false
    override suspend fun readTextFile(path: String): String? = null
    override suspend fun writeTextFile(path: String, content: String): Boolean = false
    override fun getUserHomeDirectory(): String = "/home/web"
    override fun getTempDirectory(): String = "/tmp"
}

private object WebProcessService : PlatformServices.ProcessService {
    override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig): PlatformServices.ProcessService.ProcessHandle? {
        // PTY not available in browser — requires WebSocket bridge to server-side PTY
        return null
    }
}

private object WebPlatformInfo : PlatformServices.PlatformInfo {
    override fun getPlatformName(): String = "Web"
    override fun getOSName(): String = "Browser"
    override fun getOSVersion(): String {
        return try {
            val ua: JsString = js("navigator.userAgent").unsafeCast<JsString>()
            ua.toString()
        } catch (_: Throwable) {
            "Unknown"
        }
    }
    override fun isMobile(): Boolean = false
    override fun isDesktop(): Boolean = false
    override fun isWeb(): Boolean = true
}

private object WebBrowserService : PlatformServices.BrowserService {
    override suspend fun openUrl(url: String): Boolean {
        return try {
            js("window.open(url, '_blank')")
            true
        } catch (_: Throwable) {
            false
        }
    }
}

private object WebNotificationService : PlatformServices.NotificationService {
    override suspend fun showNotification(title: String, message: String) {
        try {
            js("""
                if ('Notification' in window && Notification.permission === 'granted') {
                    new Notification(title, { body: message });
                } else if ('Notification' in window && Notification.permission !== 'denied') {
                    Notification.requestPermission().then(function(permission) {
                        if (permission === 'granted') {
                            new Notification(title, { body: message });
                        }
                    });
                }
            """)
        } catch (_: Throwable) {
            // Notifications not available
        }
    }

    override fun beep() {
        try {
            js("""
                if (typeof AudioContext !== 'undefined') {
                    var ctx = new AudioContext();
                    var osc = ctx.createOscillator();
                    osc.type = 'sine';
                    osc.frequency.setValueAtTime(800, ctx.currentTime);
                    osc.connect(ctx.destination);
                    osc.start();
                    osc.stop(ctx.currentTime + 0.1);
                }
            """)
        } catch (_: Throwable) {
            // Audio not available
        }
    }
}
