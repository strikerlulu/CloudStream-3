package com.lagradost.cloudstream3.sycnplay;

//import org.json.JSONException;
//import org.json.JSONObject;

import com.lagradost.cloudstream3.utils.Coroutines;

import org.json.JSONException;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

//@SuppressWarnings("unchecked")
public class SyncPlayClient implements Runnable {
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
    private Long serverIgnoringOnTheFly = 0L;
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

    private JSONObject helloRequest() throws JSONException {
        JSONObject payload = new JSONObject();
        JSONObject hello = new JSONObject();
        hello.put("username", this.username);
        JSONObject room = new JSONObject();
        room.put("name", this.room);
        hello.put("room", room);
        hello.put("version", version);
        if (this.password != null) {
            hello.put("password", this.password);
        }
        payload.put("Hello", hello);
        return payload;
    }

    private JSONObject listRequest() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("List", null);
        return payload;
    }

    private void sendFrame(String frame) {
        sPlayInterface.debugMessage("CLIENT >> " + frame);
        pw.println(frame + "\r\n");
        pw.flush();
    }

    @SuppressWarnings("ConstantConditions")
    @Override
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
                        JSONParser jParse = new JSONParser();
                        JSONObject jObj = (JSONObject) jParse.parse(response);
                        if (!jObj.toJSONString().contains("ping"))
                            System.out.println(jObj.toJSONString());
                        if (jObj.containsKey("Error")) {
                            //disconnect
                            this.isConnected = false;
                            sPlayInterface.onError(jObj.get("Error").toString());
                        }
                        if (jObj.containsKey("Hello")) {
                            JSONObject Hello = (JSONObject) jObj.get("Hello");
                            String motd = Hello.get("motd").toString();
                            sendFrame(roomEventRequest("joined").toString());
                            sPlayInterface.onConnected(motd);
                        }
                        if (jObj.containsKey("Chat")) {
                            JSONObject Hello = (JSONObject) jObj.get("Chat");
                            String username = Hello.get("username").toString();
                            String message = Hello.get("message").toString();
                            sPlayInterface.onChat(message, username);
                        }
                        if (jObj.containsKey("Set")) {
                            JSONObject set = (JSONObject) jObj.get("Set");
                            if (set.containsKey("user")) {
                                JSONObject user = (JSONObject) set.get("user");
                                Set<String> uKeySet = user.keySet();
                                for (String userName : uKeySet) {
                                    JSONObject specificUser = (JSONObject) user.get(userName);
                                    JSONObject room = (JSONObject) specificUser.get("room");
                                    String roomName = (String) room.get("name");

                                    if (specificUser.containsKey("event")) {
                                        JSONObject event = (JSONObject) specificUser.get("event");
                                        String eventName = null;
                                        if (event.containsKey("joined")) {
                                            eventName = "joined";
                                        } else if (event.containsKey("left")) {
                                            eventName = "left";
                                        }
                                        Boolean eventFlag = (Boolean) event.get(eventName);
                                        Map<String, Boolean> eventMap = new HashMap<>();
                                        eventMap.put(eventName, eventFlag);
                                        sPlayInterface.onUser(userName, eventMap, roomName);
                                    }
                                    if (specificUser.containsKey("file")) {
                                        JSONObject file = (JSONObject) specificUser.get("file");
                                        SyncPlayClientInterface.UserFileDetails mFileDetails;
                                        try {
                                            mFileDetails = new
                                                    SyncPlayClientInterface.UserFileDetails(
                                                    new Double((double) file.get("duration")).longValue(),
                                                    new Double((double) file.get("size")).longValue(),
                                                    (String) file.get("name"), userName);
                                        } catch (ClassCastException e) {
                                            try {
                                                mFileDetails = new
                                                        SyncPlayClientInterface.UserFileDetails(
                                                        new Double((long) file.get("duration")).longValue(),
                                                        new Double((long) file.get("size")).longValue(),
                                                        (String) file.get("name"), userName);
                                            } catch (ClassCastException f) {
                                                try {
                                                    mFileDetails = new
                                                            SyncPlayClientInterface.UserFileDetails(
                                                            new Double((long) file.get("duration")).longValue(),
                                                            new Double((double) file.get("size")).longValue(),
                                                            (String) file.get("name"), userName);
                                                } catch (ClassCastException g) {
                                                    mFileDetails = new
                                                            SyncPlayClientInterface.UserFileDetails(
                                                            new Double((double) file.get("duration")).longValue(),
                                                            new Double((long) file.get("size")).longValue(),
                                                            (String) file.get("name"), userName);
                                                }
                                            }
                                        }
                                        sPlayInterface.onFileUpdate(mFileDetails);
                                    }
                                }
                            }
                        }
                        if (jObj.containsKey("List")) {
                            Stack<SyncPlayClientInterface.UserFileDetails> details = new Stack<>();
                            JSONObject listObj = (JSONObject) jObj.get("List");
                            Set<String> roomKeys = listObj.keySet();
                            for (String room : roomKeys) {
                                if (room.equals(this.room)) {
                                    JSONObject roomObj = (JSONObject) listObj.get(room);
                                    Set<String> userKeys = roomObj.keySet();
                                    for (String user : userKeys) {
                                        if (user.equals(this.username)) {
                                            continue;
                                        }
                                        JSONObject userObj = (JSONObject) roomObj.get(user);
                                        JSONObject file = (JSONObject) userObj.get("file");
                                        if (file.containsKey("duration") && file.containsKey("size")
                                                && file.containsKey("name")) {
                                            try {
                                                details.push(new SyncPlayClientInterface.UserFileDetails(
                                                        new Double((double) file.get("duration")).longValue(), (long) file.get("size"),
                                                        (String) file.get("name"), user
                                                ));
                                            } catch (ClassCastException e) {
                                                try {
                                                    details.push(new SyncPlayClientInterface.UserFileDetails(
                                                            (long) file.get("duration"), (long) file.get("size"),
                                                            (String) file.get("name"), user
                                                    ));
                                                } catch (ClassCastException f) {
                                                    try {
                                                        details.push(new SyncPlayClientInterface.UserFileDetails(
                                                                new Double((double) file.get("duration")).longValue(),
                                                                new Double((double) file.get("size")).longValue(),
                                                                (String) file.get("name"), user
                                                        ));
                                                    } catch (ClassCastException g) {
                                                        details.push(new SyncPlayClientInterface.UserFileDetails(
                                                                (long) file.get("duration"),
                                                                new Double((double) file.get("size")).longValue(),
                                                                (String) file.get("name"), user
                                                        ));
                                                    }
                                                }
                                            }
                                        } else {
                                            details.push(new SyncPlayClientInterface.UserFileDetails(0, 0, null, user));
                                        }
                                    }
                                }
                            }
                            sPlayInterface.onUserList(details);
                        }
                        if (jObj.containsKey("State")) {
                            JSONObject state = (JSONObject) jObj.get("State");
                            JSONObject ping = (JSONObject) state.get("ping");
                            if (ping.get("yourLatency") != null) {
                                this.clientRtt = (float) ping.get("yourLatency");
                            }
                            this.latencyCalculation = (Double) ping.get("latencyCalculation");
                            if (state.containsKey("ignoringOnTheFly")) {
                                JSONObject ignore = (JSONObject) state.get("ignoringOnTheFly");
                                if (ignore.containsKey("server")) {
                                    this.serverIgnoringOnTheFly = (Long) ignore.get("server");
                                    this.clientIgnoringOnTheFly = 0;
                                    this.stateChanged = false;
                                }
                            }
                            if (state.containsKey("playstate")) {
                                JSONObject playstate = (JSONObject) state.get("playstate");
                                if (playstate.containsKey("setBy")) {
                                    String setBy = (String) playstate.get("setBy");
                                    if ((setBy != null) && (!setBy.equals(this.username))) {
                                        Boolean paused = (Boolean) playstate.get("paused");
                                        Boolean doSeek = (Boolean) playstate.get("doSeek");
                                        if (doSeek == null) {
                                            doSeek = false;
                                        }
                                        long position;
                                        try {
                                            position = new Double((double) playstate.get("position")).longValue();
                                        } catch (ClassCastException e) {
                                            try {
                                                position = (long) playstate.get("position");
                                            } catch (ClassCastException f) {
                                                position = new Double((double) playstate.get("position")).longValue();
                                            }
                                        }
                                        sPlayInterface.onUser(setBy, paused, position, doSeek);
                                    }
                                }
                            }
                            sendFrame(prepareState().toString());
                            this.pongTimeKeeper = System.currentTimeMillis();
                        }
                    } catch (ParseException e) {
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
            } catch (IOException | JSONException e) {
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

    private JSONObject prepareState() {
        JSONObject payload = new JSONObject();
        JSONObject state = new JSONObject();
        Boolean clientIgnoreIsNotSet = (clientIgnoringOnTheFly == 0 || serverIgnoringOnTheFly != 0);
        if (clientIgnoreIsNotSet) {
            state.put("playstate", new JSONObject());
            JSONObject playstate = (JSONObject) state.get("playstate");
            if (mPlayerDetails != null) {
                Coroutines.INSTANCE.runOnMainThread(() -> {
                    playstate.put("position", mPlayerDetails.getPosition());
                    playstate.put("paused", mPlayerDetails.isPaused());
                    return null;
                });
            } else {
                playstate.put("position", 0.0);
                playstate.put("paused", true);
            }
            if (seek) {
                playstate.put("doSeek", true);
                this.seek = false;
            }
        }
        state.put("ping", new JSONObject());
        JSONObject ping = (JSONObject) state.get("ping");
        ping.put("latencyCalculation", latencyCalculation);
        ping.put("clientLatencyCalculation", System.currentTimeMillis() / 1000);
        ping.put("clientRtt", this.clientRtt);
        if (this.stateChanged) {
            this.clientIgnoringOnTheFly += 1;
        }
        if (this.serverIgnoringOnTheFly > 0 || this.clientIgnoringOnTheFly > 0) {
            state.put("ignoringOnTheFly", new JSONObject());
            JSONObject ignoringOnTheFly = (JSONObject) state.get("ignoringOnTheFly");
            if (this.serverIgnoringOnTheFly > 0) {
                ignoringOnTheFly.put("server", this.serverIgnoringOnTheFly);
                this.serverIgnoringOnTheFly = 0L;
            }
            if (this.clientIgnoringOnTheFly > 0) {
                ignoringOnTheFly.put("client", this.clientIgnoringOnTheFly);
            }
        }
        payload.put("State", state);
        return payload;
    }

    private JSONObject roomEventRequest(String event) {
        JSONObject payload = new JSONObject();
        if (event.toLowerCase().contentEquals("joined")) {
            JSONObject set = new JSONObject();
            JSONObject user = new JSONObject();
            JSONObject userVal = new JSONObject();
            JSONObject roomObj = new JSONObject();
            JSONObject evt = new JSONObject();
            evt.put(event, true);
            roomObj.put("name", this.room);
            roomObj.put("event", evt);
            userVal.put("room", roomObj);
            user.put(this.username, userVal);
            set.put("user", user);
            payload.put("Set", set);
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

    private JSONObject prepareReady(boolean manuallyInitiated) {
        JSONObject payload = new JSONObject();
        JSONObject set = new JSONObject();
        JSONObject ready = new JSONObject();
        ready.put("isReady", this.isReady);
        ready.put("username", this.username);
        ready.put("manuallyInitiated", manuallyInitiated);
        set.put("ready", ready);
        payload.put("Set", set);
        return payload;
    }

    public void requestList() {
        this.listRequested = true;
    }

    public void setMsg(String msg) {
        this.message = msg;
        this.sendMsg = true;
    }

    private JSONObject prepareFile() {
        JSONObject payload = new JSONObject();
        JSONObject set = new JSONObject();
        JSONObject file = new JSONObject();
        file.put("duration", this.duration);
        file.put("name", this.filename);
        file.put("size", this.size);
        set.put("file", file);
        payload.put("Set", set);
        return payload;
    }

    private JSONObject prepareMessage() {
        JSONObject payload = new JSONObject();
        payload.put("Chat", this.message);
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
