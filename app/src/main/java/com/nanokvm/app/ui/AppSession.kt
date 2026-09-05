package com.nanokvm.app.ui

/**
 * Holds the resolved connection credentials between the connect screen and the
 * console screen (avoids leaking creds through navigation routes). A single device
 * for the MVP; host profiles become a real table in a later phase.
 */
object AppSession {
    var host: String = ""
        private set
    var username: String = ""
        private set
    var password: String = ""
        private set

    fun set(host: String, username: String, password: String) {
        this.host = host
        this.username = username
        this.password = password
    }
}