// Log.kt
// A desktop stand-in for android.util.Log. The vendored app layer (ChatStore, NostrTransport)
// calls android.util.Log.d by its fully qualified name for debug tracing. Compiling those files
// verbatim needs the symbol to exist; PGPonyDesktop carries the same shim for the same reason.
//
// Silent by default: these traces include mailbox suffixes and counters, which do not belong on
// a user's terminal. Set CARRIERPONY_DEBUG=1 to print them to stderr.

package android.util

object Log {
    private val enabled: Boolean = System.getenv("CARRIERPONY_DEBUG") == "1"

    private fun emit(level: String, tag: String?, msg: String?, tr: Throwable? = null): Int {
        if (enabled) {
            System.err.println("$level/${tag ?: "-"}: ${msg ?: ""}")
            tr?.printStackTrace(System.err)
        }
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String?): Int = emit("V", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?): Int = emit("D", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?): Int = emit("I", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?): Int = emit("W", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = emit("W", tag, msg, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = emit("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = emit("E", tag, msg, tr)
}
