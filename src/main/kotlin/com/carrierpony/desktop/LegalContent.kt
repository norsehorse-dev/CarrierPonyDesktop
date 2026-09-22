// LegalContent.kt
// CarrierPony Desktop. The privacy policy and terms, shown from Settings. English is
// authoritative and these are constants, not resources: a legal text is one document, not a set
// of strings a translator rewrites sentence by sentence.
//
// Adapted from Android's ui/LegalScreens.kt (DRIFT WATCH: ui/ is not vendored). The desktop
// differences are stated where they apply: there is no push service here (the app polls the relay
// while it runs), and there is no Reset App; an account is removed from Settings.

package com.carrierpony.desktop

object LegalContent {

    const val updated = "Last updated: September 18, 2026"

    val privacySections: List<Pair<String, String>> = listOf(
        "Overview" to
            "CarrierPony is a private messenger and file-transfer app built to know as little about " +
            "you as possible. There are no accounts: you never give us a name, email address, or " +
            "phone number. Your messages and files are end to end encrypted with OpenPGP, and only " +
            "you and the people you pair with can read them.",

        "What we do not collect" to
            "We do not collect your name, email address, or phone number. We do not upload your " +
            "contact list. We cannot read your messages or files: they are encrypted on your computer " +
            "before they are sent. The app contains no advertising, no analytics, and no trackers.",

        "What the relay processes" to
            "To deliver messages between devices, the app talks to the CarrierPony relay " +
            "(api.carrierpony.com), or to a relay you host yourself. The relay only ever sees sealed, " +
            "encrypted envelopes: it learns no names, no message contents, and no plaintext. Envelopes " +
            "are held only as long as needed to deliver them. The app also sends the relay a random " +
            "per-install device identifier (32 hex characters, generated on your computer) so envelopes " +
            "can be routed to you. This identifier is not derived from your identity key or from " +
            "anything about you personally.",

        "Notifications" to
            "The desktop app uses no push service. While it runs, it polls the relay for new " +
            "envelopes and shows a system notification if you have allowed it. Notifications carry no " +
            "message content unless you choose to show the sender's name in Settings.",

        "Local network delivery" to
            "If you turn on direct delivery on the local network, the app announces itself to other " +
            "CarrierPony devices on the same network and exchanges messages with paired contacts " +
            "directly. This shares your computer's local address with those contacts. It is off by " +
            "default.",

        "Update check" to
            "If you turn on the update check, the app fetches a small public file from carrierpony.com " +
            "once a day to learn the latest version number. The request carries no identifier and no " +
            "information about your install. It is off by default.",

        "Your keys and your data" to
            "Your private key is generated on your computer and never leaves it, except in a backup " +
            "that you create yourself, encrypted with a passphrase only you know. Messages, files, and " +
            "contacts are stored on your computer, encrypted under your launch passphrase. Removing an " +
            "account in Settings erases its keys, contacts, and messages.",

        "Third parties" to
            "The desktop app uses no third-party services. We do not sell, rent, or share any data " +
            "for advertising or marketing purposes.",

        "Children" to
            "CarrierPony is not directed at children under 13 (or the higher minimum age that applies " +
            "where you live), and we do not knowingly process their data.",

        "Changes to this policy" to
            "If this policy changes, the updated version will ship with the app and be noted in the " +
            "release notes. Continued use of the app after a change means the updated policy applies.",

        "Contact" to
            "Questions about privacy? Contact us at support@carrierpony.com."
    )

    val termsSections: List<Pair<String, String>> = listOf(
        "Acceptance of these terms" to
            "By installing or using CarrierPony you agree to these Terms of Service. If you do not " +
            "agree, please do not use the app.",

        "The service" to
            "CarrierPony provides end-to-end encrypted messaging and file transfer between paired " +
            "devices, delivered through a relay server that forwards sealed envelopes. The app " +
            "requires no account and is provided free of charge.",

        "Your responsibilities" to
            "Your identity lives in a private key on your computer. You are responsible for keeping " +
            "your computer, your launch passphrase, and your backup passphrase safe. Encrypted backups " +
            "can only be opened with the passphrase you chose; if you lose both the computer and the " +
            "passphrase, your identity and messages cannot be recovered by anyone, including us.",

        "Acceptable use" to
            "You agree not to use CarrierPony to send spam, to harass or threaten others, or to store " +
            "or share unlawful content, including child sexual abuse material. Recipients can report " +
            "abusive contacts; we review reports and may block a device's access to the relay when " +
            "the reported conduct violates these terms or applicable law.",

        "Encryption" to
            "Messages and files are end to end encrypted. We cannot read, restore, or hand over their " +
            "contents. You are responsible for complying with the laws that apply to your use of " +
            "encryption software in your country.",

        "Open source" to
            "Portions of CarrierPony are built on open-source software, including Bouncy Castle, " +
            "JmDNS, and ZXing. Those components remain under their own licenses; see the NOTICE file " +
            "that ships with the app.",

        "No warranty" to
            "CarrierPony is provided \"as is\" and \"as available\", without warranties of any kind, " +
            "express or implied. We do not guarantee that the service will be uninterrupted, timely, " +
            "or error-free.",

        "Limitation of liability" to
            "To the maximum extent permitted by law, the developer of CarrierPony is not liable for " +
            "any indirect, incidental, special, consequential, or exemplary damages arising from your " +
            "use of the app, including loss of data or messages.",

        "Termination" to
            "You can stop using CarrierPony at any time; removing your accounts in Settings and " +
            "uninstalling the app erases everything on your computer. We may suspend relay access " +
            "for devices that violate these terms.",

        "Changes to these terms" to
            "We may update these terms from time to time. The current version always ships with the " +
            "app. Continued use after a change means the updated terms apply.",

        "Contact" to
            "Questions about these terms? Contact us at support@carrierpony.com."
    )
}
