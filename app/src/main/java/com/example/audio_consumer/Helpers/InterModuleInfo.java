package com.example.audio_consumer.Helpers;

public class InterModuleInfo {

    // LoginActivity broadcast intent info
    public static int
            CHANNEL = 0,
            USER_NAME = 1,
            SEGMENT_INTEREST_MAX_REATTEMPTS = 2,
            SEGMENT_INTEREST_LIFETIME = 3,
            AP_IP_ADDRESS = 4;
    public static String
            LoginActivity_CONFIG = "LoginActivity_CONFIG";

    // MainActivity broadcast intent info
    public static String
            MainActivity_RECORD_REQUEST_START = "MainActivity_RECORD_REQUEST_START",
            MainActivity_RECORD_REQUEST_STOP = "MainActivity_RECORD_REQUEST_STOP";

    // PTTButtonPressReceiver broadcast intent info
    public static String PTTButtonPressReceiver_PTT_BUTTON_DOWN =
            "PTTButtonPressReceiver_PTT_BUTTON_DOWN";
    public static String PTTButtonPressReceiver_PTT_BUTTON_UP =
            "PTTButtonPressReceiver_PTT_BUTTON_UP";

    // Recorder broadcast intent info
    public static String
            Recorder_RECORD_REQUEST_START = "Recorder_RECORD_REQUEST_START",
            Recorder_RECORD_REQUEST_STOP = "Recorder_RECORD_REQUEST_STOP";

    // Streamer broadcast intent info
    public static String
            Streamer_RECORDING_STARTED = "Streamer_RECORDING_STARTED",
            Streamer_RECORDING_FINISHED = "Streamer_RECORDING_FINISHED";

}