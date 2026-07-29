package pro.getline.vpn.getlineui.util

typealias Validator = (String) -> Boolean

val ValidatorAcceptAll: Validator = {
    true
}

val ValidatorHttpUrl: Validator = {
    it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
}
