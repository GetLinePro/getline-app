package pro.getline.vpn.getline.auth

/**
 * Supported GetLine sign-in methods.
 *
 * Browser methods share native PKCE + capability browser launch; [Email] uses
 * in-app OTP and never opens a browser start path.
 */
enum class AuthMethod {
    Telegram,
    Google,
    Email,
    ;

    fun requiresBrowser(): Boolean = this != Email
}
