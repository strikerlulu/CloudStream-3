package com.lagradost.cloudstream3.sycnplay

import java.io.Serializable
import java.util.*

interface SyncPlayClientInterface {
    class UserFileDetails internal constructor(
        val duration: Long,
        val size: Long,
        val filename: String,
        val username: String
    ) : Serializable

    interface PlayerDetails {
        val position: Long
        val isPaused: Boolean
    }

    fun onConnected(motd: String)
    fun onChat(msg: String, username: String)
    fun onError(errMsg: String)
    fun onUser(username: String, event: Map<String, Boolean>, room: String)
    fun onUser(setBy: String, paused: Boolean, position: Long, doSeek: Boolean)
    fun onUserList(details: Stack<UserFileDetails>)
    fun onFileUpdate(mUserFileDetails: UserFileDetails)
    fun debugMessage(msg: String)
}