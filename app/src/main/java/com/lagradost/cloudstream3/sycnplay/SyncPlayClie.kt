package com.lagradost.cloudstream3.sycnplay

import com.lagradost.cloudstream3.sycnplay.SyncPlayClientInterface.PlayerDetails
import com.lagradost.cloudstream3.sycnplay.SyncPlayClientInterface.UserFileDetails
import com.lagradost.cloudstream3.utils.Coroutines
import org.json.JSONException
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.json.simple.parser.ParseException
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import kotlin.concurrent.thread

class SyncPlayClie(
    private val address: String,
    private val port: Int,
    private val room: String,
    private val username: String,
    private var password: String,
    private val sPlayInterface: SyncPlayClientInterface
) {
    val version = "1.4.0"
    private lateinit var pw: PrintWriter
    private var isConnected = false
    private var isReady = false

    private var filename: String? = null
    private var duration: Long? = null
    private var size: Long? = null
    private var clientRtt = 0f
    private var latencyCalculation: Double? = null

    private var clientIgnoringOnTheFly = 0
    private var serverIgnoringOnTheFly = 0L
    private var stateChanged = false
    private var seek = false

    private val frameArray = mutableListOf<String>()
    private var latency = 0L
    private var pongTimeKeeper = System.currentTimeMillis()
    private var triggerFile = false
    private var listRequested = false
    private var mPlayerDetails: PlayerDetails? = null

    init {
        if (password.isNotBlank()) {
            password = Utils.md5(password)
        }
    }

    @Throws(JSONException::class)
    private fun helloRequest(): JSONObject {
        val payload = JSONObject()
        val hello = JSONObject()
        hello["username"] = username
        val room = JSONObject()
        room["name"] = this.room
        hello["room"] = room
        hello["version"] = SyncPlayClient.version
        if (password.isNotEmpty()) {
            hello["password"] = password
        }
        payload["Hello"] = hello
        return payload
    }

    @Throws(JSONException::class)
    private fun listRequest(): JSONObject {
        val payload = JSONObject()
        payload["List"] = null
        return payload
    }

    private fun sendFrame(frame: String) {
        sPlayInterface.debugMessage("CLIENT >> $frame")
        pw.println(frame + "\r\n")
        pw.flush()
    }

    fun start() {
        thread {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), 3000)
            } catch (e: IOException) {
                e.printStackTrace()
                sPlayInterface.onError(e.toString())
            }
            if (socket.isConnected) {
                try {
                    isConnected = true
                    pw =
                        PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())))
                    sendFrame(helloRequest().toString())
                    sendFrame(listRequest().toString())
                    var bufferedReader: BufferedReader? = null
                    try {
                        bufferedReader =
                            BufferedReader(InputStreamReader(socket.getInputStream(), "utf8"))
                    } catch (e: IOException) {
                        e.printStackTrace();
                        sPlayInterface.onError(e.toString());
                    }
                    var response = ""
                    try {
                        if (bufferedReader != null) {
                            response = bufferedReader.readLine()
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        sPlayInterface.onError(e.toString())
                    }
                    while (isConnected) {
                        for (frame in frameArray) {
                            sendFrame(frame)
                        }
                        frameArray.clear()
                        latency = System.currentTimeMillis() - pongTimeKeeper
                        sPlayInterface.debugMessage("SERVER << $response")
                        try {
                            val jParse = JSONParser()
                            val jObj = jParse.parse(response) as JSONObject
                            if (jObj.containsKey("Error")) {
                                isConnected = false //disconnect
                                sPlayInterface.onError(jObj["Error"].toString())
                            }
                            if (jObj.containsKey("Hello")) {
                                val Hello = jObj["Hello"] as JSONObject
                                val motd = Hello["motd"].toString()
                                sendFrame(roomEventRequest("joined").toString())
                                sPlayInterface.onConnected(motd)
                            }
                            if (jObj.containsKey("Set")) {
                                val set = jObj["Set"] as JSONObject
                                if (set.containsKey("user")) {
                                    val user = set["user"] as JSONObject
                                    val uKeySet: MutableSet<String> =
                                        user.keys as MutableSet<String>
                                    for (userName in uKeySet) {
                                        val specificUser = user[userName] as JSONObject
                                        val room = specificUser["room"] as JSONObject
                                        val roomName = room["name"] as String
                                        if (specificUser.containsKey("event")) {
                                            val event = specificUser["event"] as JSONObject
                                            println(event)
                                            val eventName: String? = event[0] as String?
                                            val eventFlag: Boolean? = event[eventName] as Boolean?
                                            if (eventName != null || eventFlag != null) {
                                                val eventMap: MutableMap<String, Boolean> =
                                                    HashMap()
                                                eventMap[eventName!!] = eventFlag!!
                                                sPlayInterface.onUser(
                                                    userName,
                                                    eventMap, roomName
                                                )
                                            }
                                        }
                                        if (specificUser.containsKey("file")) {
                                            val file = specificUser["file"] as JSONObject
                                            val mFileDetails = UserFileDetails(
                                                try {
                                                    (file["duration"] as Double).toLong()
                                                } catch (e: ClassCastException) {
                                                    file["duration"] as Long
                                                },
                                                file["size"] as Long,
                                                file["name"] as String, userName
                                            )
                                            sPlayInterface.onFileUpdate(mFileDetails)
                                        }
                                    }
                                }
                            }
                            /* if (jObj.containsKey("List")) {
                            val details = Stack<UserFileDetails?>()
                            val listObj = jObj["List"] as JSONObject
                            val roomKeys: MutableSet<String> = listObj.keys as MutableSet<String>
                            for (room in roomKeys) {
                                if (room == this.room) {
                                    val roomObj = listObj[room] as JSONObject
                                    val userKeys: MutableSet<String> = roomObj.keys as MutableSet<String>
                                    for (user in userKeys) {
                                        if (user == username) {
                                            continue
                                        }
                                        val userObj = roomObj[user] as JSONObject
                                        val file = userObj["file"] as JSONObject
                                        if (file.containsKey("duration") && file.containsKey("size")
                                            && file.containsKey("name")
                                        ) {
                                            try {
                                                details.push(
                                                    UserFileDetails(
                                                        file["duration"] as Double
                                                        (),
                                                        file["size"] as Double,
                                                        (file["name"] as String?)!!,
                                                        user
                                                    )
                                                )
                                            } catch (e: ClassCastException) {
                                                try {
                                                    details.push(
                                                        UserFileDetails(
                                                            file["duration"] as Double,
                                                            file["size"] as Double,
                                                            (file["name"] as String?)!!,
                                                            user!!
                                                        )
                                                    )
                                                } catch (f: ClassCastException) {
                                                    try {
                                                        details.push(
                                                            UserFileDetails(
                                                                file["duration"] as Double.toLong(),
                                                                file["size"] as Double.toLong(),
                                                                file["name"] as String?, user
                                                            )
                                                        )
                                                    } catch (g: ClassCastException) {
                                                        details.push(
                                                            UserFileDetails(
                                                                file["duration"] as Double,
                                                                file["size"] as Double.toLong(),
                                                                (file["name"] as String?)!!, user
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            details.push(UserFileDetails(0, 0, null, user!!))
                                        }
                                    }
                                }
                            }
                            sPlayInterface.onUserList(details)
                        }
                            */
                            if (jObj.containsKey("State")) {
                                val state = jObj["State"] as JSONObject
                                val ping = state["ping"] as JSONObject
                                if (ping["yourLatency"] != null) {
                                    this.clientRtt = ping["yourLatency"] as Float
                                }
                                this.latencyCalculation = ping["latencyCalculation"] as Double
                                if (state.containsKey("ignoringOnTheFly")) {
                                    val ignore = state["ignoringOnTheFly"] as JSONObject
                                    if (ignore.containsKey("server")) {
                                        this.serverIgnoringOnTheFly = ignore["server"] as Long
                                        this.clientIgnoringOnTheFly = 0
                                        this.stateChanged = false
                                    }
                                }
                                if (state.containsKey("playstate")) {
                                    val playState = state["playstate"] as JSONObject
                                    if (playState.containsKey("setBy")) {
                                        val setBy = playState["setBy"] as String?
                                        if (setBy != null && setBy != username) {
                                            val paused = playState["paused"] as Boolean
                                            var doSeek = playState["doSeek"] as Boolean?
                                            if (doSeek == null) doSeek = false
                                            val position = try {
                                                (playState["position"] as Double).toLong()
                                            } catch (e: ClassCastException) {
                                                playState["position"] as Long
                                            }
                                            println("debug: his position is ${position * 1000}")
                                            sPlayInterface.onUser(
                                                setBy,
                                                paused,
                                                position,
                                                doSeek
                                            )
                                        }
                                    }
                                }
                                sendFrame(prepareState().toString())
                                pongTimeKeeper = System.currentTimeMillis()
                            }
                        } catch (e: ParseException) {
                            e.printStackTrace();
                        }
                        response = bufferedReader?.readLine() ?: ""
                        if (triggerFile) {
                            triggerFile = false
                            sendFrame(prepareFile().toString())
                        }
                        if (listRequested) {
                            this.listRequested = false
                            sendFrame(listRequest().toString())
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    sPlayInterface.onError(e.toString())
                } catch (e: JSONException) {
                    e.printStackTrace()
                    sPlayInterface.onError(e.toString())
                }
                try {
                    socket.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                    sPlayInterface.onError(e.toString())
                }
            }
        }
    }

    fun disconnect() {
        isConnected = false
    }

    private fun prepareState(): JSONObject {
        val payload = JSONObject()
        val state = JSONObject()
        val clientIgnoreIsNotSet = clientIgnoringOnTheFly == 0 || serverIgnoringOnTheFly != 0L
        if (clientIgnoreIsNotSet) {
            state["playstate"] = JSONObject()
            val playState = state["playstate"] as JSONObject
            if (mPlayerDetails != null) {
                Coroutines.runOnMainThread {
                    println("debug: my position is ${mPlayerDetails!!.position}")
                    playState["position"] = mPlayerDetails!!.position
                    playState["paused"] = mPlayerDetails!!.isPaused
                }
            } else {
                playState["position"] = 0.0
                playState["paused"] = true
            }
            if (seek) {
                playState["doSeek"] = true
                seek = false
            }
        }
        state["ping"] = JSONObject()
        val ping = state["ping"] as JSONObject
        ping["latencyCalculation"] = latencyCalculation
        ping["clientLatencyCalculation"] = System.currentTimeMillis() / 1000
        ping["clientRtt"] = clientRtt
        if (stateChanged) {
            clientIgnoringOnTheFly += 1
        }
        if (serverIgnoringOnTheFly > 0 || clientIgnoringOnTheFly > 0) {
            state["ignoringOnTheFly"] = JSONObject()
            val ignoringOnTheFly = state["ignoringOnTheFly"] as JSONObject
            if (serverIgnoringOnTheFly > 0) {
                ignoringOnTheFly["server"] = serverIgnoringOnTheFly
                serverIgnoringOnTheFly = 0L
            }
            if (clientIgnoringOnTheFly > 0) {
                ignoringOnTheFly["client"] = clientIgnoringOnTheFly
            }
        }
        payload["State"] = state
        return payload
    }

    private fun roomEventRequest(event: String): JSONObject {
        val payload = JSONObject()
        if (event.lowercase(Locale.getDefault()).contentEquals("joined")) {
            val set = JSONObject()
            val user = JSONObject()
            val userVal = JSONObject()
            val roomObj = JSONObject()
            val evt = JSONObject()
            evt[event] = true
            roomObj["name"] = room
            roomObj["event"] = evt
            userVal["room"] = roomObj
            user[username] = userVal
            set["user"] = user
            payload["Set"] = set
        }
        return payload
    }

    fun setFile(duration: Long, size: Long, filename: String) {
        this.filename = filename
        this.duration = duration
        this.size = size
        triggerFile = true
    }

    fun setReady(isReady: Boolean) {
        this.isReady = isReady
        frameArray.add(prepareReady(true).toString())
    }

    private fun prepareReady(manuallyInitiated: Boolean): JSONObject {
        val payload = JSONObject()
        val set = JSONObject()
        val ready = JSONObject()
        ready["isReady"] = isReady
        ready["username"] = username
        ready["manuallyInitiated"] = manuallyInitiated
        set["ready"] = ready
        payload["Set"] = set
        return payload
    }

    fun requestList() {
        listRequested = true
    }

    private fun prepareFile(): JSONObject {
        val payload = JSONObject()
        val set = JSONObject()
        val file = JSONObject()
        file["duration"] = duration
        file["name"] = filename
        file["size"] = size
        set["file"] = file
        payload["Set"] = set
        return payload
    }

    fun setPlayerState(pd: PlayerDetails) {
        this.mPlayerDetails = pd
    }

    fun playPause() {
        stateChanged = true
    }

    fun seeked() {
        seek = true
        playPause()
    }

    fun getLatency(): Long = latency
}