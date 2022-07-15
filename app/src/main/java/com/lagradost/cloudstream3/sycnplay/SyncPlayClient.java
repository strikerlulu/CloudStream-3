package com.lagradost.cloudstream3.sycnplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lagradost.cloudstream3.utils.Coroutines;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

@SuppressWarnings("unchecked")
public class SyncPlayClient {
    public static final String version = "1.6.9";
    private String username;
    private String room;
    private String password;

    private Boolean triggerFile = false;
    private Boolean listRequested = false;
    private Boolean sendMsg = false;

    private String address;
    private int port;

    private String filename;
    private Long duration;
    private Long size;
    private Float clientRtt = 0F;
    private Double latencyCalculation;

    private Integer clientIgnoringOnTheFly = 0;
    private Long serverIgnoringOnTheFly = 1L;
    private boolean stateChanged = false;
    private boolean seek = false;

    private SyncPlayClientInterface sPlayInterface;
    private boolean isConnected;
    private boolean isReady;
    private PrintWriter pw;
    private SyncPlayClientInterface.PlayerDetails mPlayerDetails;

    private long latency = 0L;
    private long pongTimeKeeper = System.currentTimeMillis();

    private ArrayList<String> frameArray;
    private String message;

    public SyncPlayClient(String username, String room, String address, String password,
                          SyncPlayClientInterface mSyncPlayClientInterface) {
        this.sPlayInterface = mSyncPlayClientInterface;
        this.username = username;
        this.room = room;
        this.address = address.split(":")[0];
        try {
            this.port = Integer.parseInt(address.split(":")[1]);
        } catch (ArrayIndexOutOfBoundsException e) {
            this.port = 80;
        }
        if (password != null && !password.isEmpty()) {
            this.password = Utils.md5(password);
        } else {
            this.password = "";
        }
        frameArray = new ArrayList<>();
    }

    private JsonObject helloRequest() throws JSONException {
        JsonObject payload = new JsonObject();
        JsonObject hello = new JsonObject();
        hello.addProperty("username", this.username);
        JsonObject room = new JsonObject();
        room.addProperty("name", this.room);
        hello.add("room", room);
        hello.addProperty("version", version);
        if (this.password != null) {
            hello.addProperty("password", this.password);
        }
        payload.add("Hello", hello);
        return payload;
    }

    private JsonObject listRequest() throws JSONException {
        JsonObject payload = new JsonObject();
        payload.add("List", null);
        return payload;
    }

    private void sendFrame(String frame) {
        sPlayInterface.debugMessage("CLIENT >> " + frame);
        pw.println(frame + "\r\n");
        pw.flush();
    }

    @SuppressWarnings("ConstantConditions")
//    @Override
    public void run() {
        Socket socket = new Socket();
//        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try {
//            SSLSocket socket = (SSLSocket) factory.createSocket("syncplay.pl", 443);
            socket.connect(new InetSocketAddress(this.address, this.port), 3000);
        } catch (IOException e) {
            e.printStackTrace();
            sPlayInterface.onError(e.toString());
        }
        if (socket.isConnected()) {
            isConnected = true;
            try {
                pw = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream())));
                sendFrame(helloRequest().toString());
                sendFrame(listRequest().toString());
                BufferedReader bufferedreader = null;
                try {
                    bufferedreader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), "utf8"));
                } catch (IOException e) {
                    e.printStackTrace();
                    sPlayInterface.onError(e.toString());
                }
                String response = "";
                try {
                    assert bufferedreader != null;
                    response = bufferedreader.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                    sPlayInterface.onError(e.toString());
                }
                while (response != null && isConnected) {
                    for (String frame : frameArray) {
                        sendFrame(frame);
                    }
                    frameArray.clear();

                    this.latency = System.currentTimeMillis() - this.pongTimeKeeper;
                    sPlayInterface.debugMessage("SERVER << " + response);
                    try {
                        JsonParser jParse = new JsonParser();
                        JsonObject jObj = (JsonObject) jParse.parse(response);
//                        if (!jObj.toString().contains("ping"))
//                            System.out.println(jObj);
                        if (jObj.has("Error")) {
                            //disconnect
                            this.isConnected = false;
                            sPlayInterface.onError(jObj.get("Error").toString());
                        }
                        if (jObj.has("Hello")) {
                            JsonObject Hello = (JsonObject) jObj.get("Hello");
                            String motd = Hello.get("motd").toString();
                            sendFrame(roomEventRequest("joined").toString());
                            sPlayInterface.onConnected(motd);
                        }
                        if (jObj.has("Chat")) {
                            JsonObject Hello = (JsonObject) jObj.get("Chat");
                            String username = Hello.get("username").getAsString();
                            String message = Hello.get("message").getAsString();
                            sPlayInterface.onChat(message, username);
                        }
                        if (jObj.has("Set")) {
                            JsonObject set = (JsonObject) jObj.get("Set");
                            if (set.has("user")) {
                                JsonObject user = (JsonObject) set.get("user");
                                Set<String> uKeySet = user.keySet();
                                for (String userName : uKeySet) {
                                    JsonObject specificUser = (JsonObject) user.get(userName);
                                    JsonObject room = (JsonObject) specificUser.get("room");
                                    String roomName = room.get("name").getAsString();

                                    if (specificUser.has("event")) {
                                        JsonObject event = (JsonObject) specificUser.get("event");
                                        String eventName = null;
                                        if (event.has("joined")) {
                                            eventName = "joined";
                                        } else if (event.has("left")) {
                                            eventName = "left";
                                        }
                                        Boolean eventFlag = event.get(eventName).getAsBoolean();
                                        Map<String, Boolean> eventMap = new HashMap<>();
                                        eventMap.put(eventName, eventFlag);
                                        sPlayInterface.onUser(userName, eventMap, roomName);
                                    }
                                    if (specificUser.has("file")) {
                                        JsonObject file = (JsonObject) specificUser.get("file");
                                        SyncPlayClientInterface.UserFileDetails mFileDetails;
                                        try {
                                            mFileDetails = new
                                                    SyncPlayClientInterface.UserFileDetails(
                                                    new Double(file.get("duration").getAsDouble()).longValue(),
                                                    new Double(file.get("size").getAsDouble()).longValue(),
                                                    file.get("name").getAsString(), userName);
                                        } catch (ClassCastException e) {
                                            try {
                                                mFileDetails = new
                                                        SyncPlayClientInterface.UserFileDetails(
                                                        new Double(file.get("duration").getAsDouble()).longValue(),
                                                        new Double(file.get("size").getAsDouble()).longValue(),
                                                        file.get("name").getAsString(), userName);
                                            } catch (ClassCastException f) {
                                                try {
                                                    mFileDetails = new
                                                            SyncPlayClientInterface.UserFileDetails(
                                                            new Double(file.get("duration").getAsLong()).longValue(),
                                                            new Double(file.get("size").getAsDouble()).longValue(),
                                                            file.get("name").getAsString(), userName);
                                                } catch (ClassCastException g) {
                                                    mFileDetails = new
                                                            SyncPlayClientInterface.UserFileDetails(
                                                            new Double(file.get("duration").getAsDouble()).longValue(),
                                                            new Double(file.get("size").getAsLong()).longValue(),
                                                            file.get("name").getAsString(), userName);
                                                }
                                            }
                                        }
                                        sPlayInterface.onFileUpdate(mFileDetails);
                                    }
                                }
                            }
                        }
                        if (jObj.has("List")) {
                            Stack<SyncPlayClientInterface.UserFileDetails> details = new Stack<>();
                            JsonObject listObj = (JsonObject) jObj.get("List");
                            Set<String> roomKeys = listObj.keySet();
                            for (String room : roomKeys) {
                                if (room.equals(this.room)) {
                                    JsonObject roomObj = (JsonObject) listObj.get(room);
                                    Set<String> userKeys = roomObj.keySet();
                                    for (String user : userKeys) {
                                        if (user.equals(this.username)) {
                                            continue;
                                        }
                                        JsonObject userObj = (JsonObject) roomObj.get(user);
                                        JsonObject file = (JsonObject) userObj.get("file");
                                        if (file.has("duration") && file.has("size")
                                                && file.has("name")) {
                                            try {
                                                details.push(new SyncPlayClientInterface.UserFileDetails(
                                                        new Double(file.get("duration").getAsDouble()).longValue(), file.get("size").getAsLong(),
                                                        file.get("name").getAsString(), user
                                                ));
                                            } catch (ClassCastException e) {
                                                try {
                                                    details.push(new SyncPlayClientInterface.UserFileDetails(
                                                            file.get("duration").getAsLong(), file.get("size").getAsLong(),
                                                            file.get("name").getAsString(), user
                                                    ));
                                                } catch (ClassCastException f) {
                                                    try {
                                                        details.push(new SyncPlayClientInterface.UserFileDetails(
                                                                new Double(file.get("duration").getAsDouble()).longValue(),
                                                                new Double(file.get("size").getAsDouble()).longValue(),
                                                                file.get("name").getAsString(), user
                                                        ));
                                                    } catch (ClassCastException g) {
                                                        details.push(new SyncPlayClientInterface.UserFileDetails(
                                                                file.get("duration").getAsLong(),
                                                                new Double(file.get("size").getAsDouble()).longValue(),
                                                                file.get("name").getAsString(), user
                                                        ));
                                                    }
                                                }
                                            }
                                        } else {
                                            details.push(new SyncPlayClientInterface.UserFileDetails(0, 0, "", user));
                                        }
                                    }
                                }
                            }
                            sPlayInterface.onUserList(details);
                        }
                        if (jObj.has("State")) {
                            JsonObject state = (JsonObject) jObj.get("State");
                            JsonObject ping = (JsonObject) state.get("ping");
                            if (ping.get("yourLatency") != null) {
                                this.clientRtt = ping.get("yourLatency").getAsFloat();
                            }
                            this.latencyCalculation = ping.get("latencyCalculation").getAsDouble();
                            if (state.has("ignoringOnTheFly")) {
                                JsonObject ignore = (JsonObject) state.get("ignoringOnTheFly");
                                if (ignore.has("server")) {
                                    this.serverIgnoringOnTheFly = ignore.get("server").getAsLong();
                                    this.clientIgnoringOnTheFly = 0;
                                    this.stateChanged = false;
                                }
                            }
                            if (state.has("playstate")) {
                                JsonObject playstate = (JsonObject) state.get("playstate");
                                if (playstate.has("setBy")) {
                                    try {
                                        String setBy = playstate.get("setBy").getAsString();
                                        if ((!setBy.equals(this.username))) {
                                            Boolean paused = playstate.get("paused").getAsBoolean();
                                            Boolean doSeek;
                                            try {
                                                doSeek = playstate.get("doSeek").getAsBoolean();
                                            } catch (UnsupportedOperationException e) {
                                                doSeek = false;
                                            }
                                            long position;
                                            try {
                                                position = new Double(playstate.get("position").getAsDouble()).longValue();
                                            } catch (ClassCastException e) {
                                                try {
                                                    position = playstate.get("position").getAsLong();
                                                } catch (ClassCastException f) {
                                                    position = new Double(playstate.get("position").getAsDouble()).longValue();
                                                }
                                            }
                                            sPlayInterface.onUser(setBy, paused, position, doSeek);
                                        }
                                    } catch (UnsupportedOperationException ignored) {
                                    }
                                }
                            }
                            sendFrame(prepareState().toString());
                            this.pongTimeKeeper = System.currentTimeMillis();
                        }
                    } catch (ConcurrentModificationException e) {
                        e.printStackTrace();
                    }
                    response = bufferedreader.readLine();
                    if (triggerFile) {
                        this.triggerFile = false;
                        sendFrame(prepareFile().toString());
                    }
                    if (listRequested) {
                        this.listRequested = false;
                        sendFrame(listRequest().toString());
                    }
                    if (sendMsg) {
                        this.sendMsg = false;
                        sendFrame(prepareMessage().toString());
                    }
                }
            } catch (IOException | JSONException | UnsupportedOperationException e) {
                e.printStackTrace();
                sPlayInterface.onError(e.toString());
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
                sPlayInterface.onError(e.toString());
            }
        }
    }

    public void disconnect() {
        this.isConnected = false;
    }

    public boolean isConnected() {
        return this.isConnected;
    }

    public boolean isReady() {
        return this.isReady;
    }

    private JsonObject prepareState() {
        JsonObject payload = new JsonObject();
        JsonObject state = new JsonObject();
        boolean clientIgnoreIsNotSet = (clientIgnoringOnTheFly == 0 || serverIgnoringOnTheFly != 0);
        System.out.println("The fking boolean is " + clientIgnoreIsNotSet);
//        clientIgnoreIsNotSet = true;
        if (clientIgnoreIsNotSet) {
            state.add("playstate", new JsonObject());
            JsonObject playstate = (JsonObject) state.get("playstate");
            if (mPlayerDetails != null) {
                Coroutines.INSTANCE.main(continuation -> {
                    playstate.addProperty("position", mPlayerDetails.getPosition());
                    playstate.addProperty("paused", mPlayerDetails.isPaused());
                    return null;
                });
//                Coroutines.INSTANCE.runOnMainThread(() -> {
//                    playstate.addProperty("position", mPlayerDetails.getPosition());
//                    playstate.addProperty("paused", mPlayerDetails.isPaused());
//                    return null;
//                });
            } else {
                playstate.addProperty("position", 0.0);
                playstate.addProperty("paused", true);
            }
            if (seek) {
                playstate.addProperty("doSeek", true);
                this.seek = false;
            }
        }
        state.add("ping", new JsonObject());
        JsonObject ping = (JsonObject) state.get("ping");
        ping.addProperty("latencyCalculation", latencyCalculation);
        ping.addProperty("clientLatencyCalculation", System.currentTimeMillis() / 1000);
        ping.addProperty("clientRtt", this.clientRtt);
        if (this.stateChanged) {
            this.clientIgnoringOnTheFly += 1;
        }
        if (this.serverIgnoringOnTheFly > 0 || this.clientIgnoringOnTheFly > 0) {
            state.add("ignoringOnTheFly", new JsonObject());
            JsonObject ignoringOnTheFly = (JsonObject) state.get("ignoringOnTheFly");
            if (this.serverIgnoringOnTheFly > 0) {
                ignoringOnTheFly.addProperty("server", this.serverIgnoringOnTheFly);
                this.serverIgnoringOnTheFly = 0L;
            }
            if (this.clientIgnoringOnTheFly > 0) {
                ignoringOnTheFly.addProperty("client", this.clientIgnoringOnTheFly);
            }
        }
        payload.add("State", state);
//        System.out.println("My playstate:" + payload);
        return payload;
    }

    private JsonObject roomEventRequest(String event) {
        JsonObject payload = new JsonObject();
        if (event.toLowerCase().contentEquals("joined")) {
            JsonObject set = new JsonObject();
            JsonObject user = new JsonObject();
            JsonObject userVal = new JsonObject();
            JsonObject roomObj = new JsonObject();
            JsonObject evt = new JsonObject();
            evt.addProperty(event, true);
            roomObj.addProperty("name", this.room);
            roomObj.add("event", evt);
            userVal.add("room", roomObj);
            user.add(this.username, userVal);
            set.add("user", user);
            payload.add("Set", set);
        }
        return payload;
    }

    public void set_file(long duration, long size, String filename) {
        this.filename = filename;
        this.duration = duration;
        this.size = size;
        this.triggerFile = true;
    }

    public void setReady(boolean isReady) {
        this.isReady = isReady;
        frameArray.add(prepareReady(true).toString());
    }

    private JsonObject prepareReady(boolean manuallyInitiated) {
        JsonObject payload = new JsonObject();
        JsonObject set = new JsonObject();
        JsonObject ready = new JsonObject();
        ready.addProperty("isReady", this.isReady);
        ready.addProperty("username", this.username);
        ready.addProperty("manuallyInitiated", manuallyInitiated);
        set.add("ready", ready);
        payload.add("Set", set);
        return payload;
    }

    public void requestList() {
        this.listRequested = true;
    }

    public void setMsg(String msg) {
        this.message = msg;
        this.sendMsg = true;
    }

    private JsonObject prepareFile() {
        JsonObject payload = new JsonObject();
        JsonObject set = new JsonObject();
        JsonObject file = new JsonObject();
        file.addProperty("duration", this.duration);
        file.addProperty("name", this.filename);
        file.addProperty("size", this.size);
        set.add("file", file);
        payload.add("Set", set);
        return payload;
    }

    private JsonObject prepareMessage() {
        JsonObject payload = new JsonObject();
        payload.addProperty("Chat", this.message);
        return payload;
    }

    public void setPlayerState(SyncPlayClientInterface.PlayerDetails pd) {
        this.mPlayerDetails = pd;
    }

    public void playPause() {
        this.stateChanged = true;
    }

    public void seeked() {
        this.seek = true;
        playPause();
    }

    public long getLatency() {
        return this.latency;
    }

}
