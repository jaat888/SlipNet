package app.slipnet.vpnjantit

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import app.slipnet.domain.model.DnsResolver
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType

/** VPNJantit ke free India SSH servers (website ki list se). */
data class VpnJantitServer(val code: String, val label: String)

object VpnJantit {
    val SERVERS = listOf(
        VpnJantitServer("in10", "India 10 - Mumbai"),
        VpnJantitServer("in2", "India 2 - Mumbai"),
        VpnJantitServer("in5", "India 5 - Mumbai"),
        VpnJantitServer("in6", "India 6 - Bengaluru"),
        VpnJantitServer("in1", "India 1 - Bengaluru")
    )

    /** %s = server code (in6, in10 ...). Agar site ka link badle to sirf yahi badalna hai. */
    const val CREATE_URL = "https://www.vpnjantit.com/create-free-account?server=%s&type=SSH"

    const val EXTRA_SERVER = "vj_server"
    const val EXTRA_CLEAR_COOKIES = "vj_clear_cookies"

    const val R_HOST = "vj_host"
    const val R_NS = "vj_ns"
    const val R_PUBKEY = "vj_pubkey"
    const val R_USER = "vj_user"
    const val R_PASS = "vj_pass"
    const val R_EXPIRES = "vj_expires"
    const val R_ERROR = "vj_error"
    const val R_RETRY_MS = "vj_retry_ms"

    /** Site do account ke beech ~5 minute ka gap rakhti hai; hum 6-7 minute ruk ke dobara try karte hain. */
    const val COOLDOWN_MS = 6L * 60_000L + 30_000L
}

data class VpnJantitAccount(
    val server: String,
    val host: String,
    val ns: String,
    val publicKey: String,
    val username: String,
    val password: String,
    /** 0 = expiry padh nahi paaye */
    val expiresAtMs: Long
) {
    fun toProfile(): ServerProfile = ServerProfile(
        name = "VPNJantit " + server.uppercase(),
        domain = ns,
        resolvers = listOf(DnsResolver("8.8.8.8", 53), DnsResolver("1.1.1.1", 53)),
        tunnelType = TunnelType.DNSTT_SSH,
        dnsttPublicKey = publicKey,
        sshUsername = username,
        sshPassword = password,
        sshPort = 22,
        sshHost = "127.0.0.1",
        expirationDate = expiresAtMs
    )
}

sealed interface VpnJantitResult {
    data class Ok(val account: VpnJantitAccount) : VpnJantitResult
    data class Failed(val message: String, val retryAfterMs: Long) : VpnJantitResult
    object Cancelled : VpnJantitResult
}

/** Input = server code (jaise "in6"). */
class CreateVpnJantitAccount : ActivityResultContract<String, VpnJantitResult>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(context, VpnJantitActivity::class.java).putExtra(VpnJantit.EXTRA_SERVER, input)

    override fun parseResult(resultCode: Int, intent: Intent?): VpnJantitResult {
        if (intent == null) return VpnJantitResult.Cancelled
        val err = intent.getStringExtra(VpnJantit.R_ERROR)
        if (!err.isNullOrBlank()) {
            return VpnJantitResult.Failed(err, intent.getLongExtra(VpnJantit.R_RETRY_MS, 0L))
        }
        if (resultCode != android.app.Activity.RESULT_OK) return VpnJantitResult.Cancelled
        val user = intent.getStringExtra(VpnJantit.R_USER).orEmpty()
        val pass = intent.getStringExtra(VpnJantit.R_PASS).orEmpty()
        val pub = intent.getStringExtra(VpnJantit.R_PUBKEY).orEmpty()
        if (user.isBlank() || pass.isBlank() || pub.isBlank()) {
            return VpnJantitResult.Failed("Account ki details adhuri aayi", 0L)
        }
        return VpnJantitResult.Ok(
            VpnJantitAccount(
                server = intent.getStringExtra(VpnJantit.EXTRA_SERVER).orEmpty(),
                host = intent.getStringExtra(VpnJantit.R_HOST).orEmpty(),
                ns = intent.getStringExtra(VpnJantit.R_NS).orEmpty(),
                publicKey = pub,
                username = user,
                password = pass,
                expiresAtMs = intent.getLongExtra(VpnJantit.R_EXPIRES, 0L)
            )
        )
    }
}
