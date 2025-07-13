package org.vosk.demo; // MAKE SURE THIS MATCHES YOUR PROJECT'S PACKAGE NAME

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Layout;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class VoskActivity extends Activity implements
RecognitionListener,
TextToSpeech.OnInitListener {

private static final String TAG = "VoskActivity";

// --- State Constants ---
static private final int STATE_START = 0;
static private final int STATE_READY = 1;
static private final int STATE_CALIBRATING = 2;
static private final int STATE_MIC = 3;
static private final int STATE_DONE = 4;
static private final int STATE_ERROR = 5;

private int currentState = STATE_START;

// --- Permissions ---
private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

// --- Vosk ---
private Model model;
private SpeechService speechService;
private boolean isPaused = false; // <<< ADDED: Track pause state locally

// --- UI Elements ---
private TextView resultView;
private TextView jarvisResponseView;
private Button calibrateButton;
private Button recognizeMicButton;
private ToggleButton pauseButton;
private Button cB; private Button rMB; private View pB; // Shorthands for setUiState

// --- Calibration ---
private static final int CALIBRATION_DURATION_MS = 5000;
private static final int AUDIO_SAMPLE_RATE = 16000;
private static final int AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
private static final int CALIBRATION_BUFFER_SIZE_FACTOR = 2;
private double calibratedVolumeRms = -1.0;
private final ExecutorService calibrationExecutor = Executors.newSingleThreadExecutor();
private static final String PREFS_NAME = "VoskDemoPrefs";
private static final String PREF_CALIBRATION_RMS = "calibrationRms";

// --- Audio Manager for Media Control ---
private AudioManager audioManager;

// --- Text-to-Speech ---
private TextToSpeech tts;
private boolean ttsInitialized = false;
private Locale targetLocale = Locale.US;

// --- Threading & Main Handler ---
private Handler mainHandler;


@Override
public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.main); // Ensure res/layout/main.xml exists

    mainHandler = new Handler(Looper.getMainLooper());

    // --- Initialize AudioManager ---
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

    // --- Initialize TextToSpeech ---
    Log.d(TAG, "Initializing TTS...");
    tts = new TextToSpeech(this, this); // 'this' is the OnInitListener

    loadCalibration();

    // Initialize UI elements
    resultView = findViewById(R.id.result_text);
    jarvisResponseView = findViewById(R.id.jarvis_response_text);
    calibrateButton = findViewById(R.id.calibrate_button);
    recognizeMicButton = findViewById(R.id.recognize_mic);
    pauseButton = findViewById(R.id.pause);

    // Setup scrolling
    resultView.setMovementMethod(new ScrollingMovementMethod());
    resultView.setText(R.string.recognition_log_title); // Initial title
    jarvisResponseView.setMovementMethod(new ScrollingMovementMethod());

    setUiState(STATE_START); // Initial state

    // Setup Button Listeners
    calibrateButton.setOnClickListener(v -> startCalibrationSafe());
    recognizeMicButton.setOnClickListener(view -> recognizeMicrophone());
    // Use the listener attached in XML or programmatically like this:
    pauseButton.setOnCheckedChangeListener((buttonView, isChecked) -> pause(isChecked));

    LibVosk.setLogLevel(LogLevel.INFO); // Vosk log level

    // Check Permissions
    int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
    } else {
        initModel(); // Model initialization will proceed if permission granted
    }
}

// --- TextToSpeech OnInitListener Implementation ---
@Override
public void onInit(int status) {
    if (status == TextToSpeech.SUCCESS) {
        Log.i(TAG, "TTS Engine Initialized successfully.");

        // --- Language Check ---
        int langResult = tts.setLanguage(targetLocale);
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "TTS: Language '" + targetLocale + "' is not supported or missing data.");
            Toast.makeText(this, "TTS Language (" + targetLocale.getDisplayLanguage() + ") not supported", Toast.LENGTH_SHORT).show();
            ttsInitialized = false;
            return; // Can't proceed without basic language support
        } else {
            Log.i(TAG, "TTS: Language '" + targetLocale + "' set as default.");
            // Continue to try and find a specific voice
        }

        // --- Voice Selection (Requires API Level 21+) ---
        Voice selectedVoice = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices != null && !voices.isEmpty()) {
                    Log.d(TAG, "Available TTS Voices (" + voices.size() + "):");
                    for (Voice voice : voices) {
                        // Log voice details for debugging (optional)
                        // Log.d(TAG, " - Name: " + voice.getName() + ", Locale: " + voice.getLocale() + ", Quality: " + voice.getQuality() + ", Latency: " + voice.getLatency() + ", Network: " + voice.isNetworkConnectionRequired() + ", Features: " + voice.getFeatures());

                        // Check for the target locale AND the "male" feature
                        if (voice.getLocale().equals(targetLocale) && voice.getFeatures().contains("male")) {
                            selectedVoice = voice;
                            Log.i(TAG, "Found suitable male voice: " + selectedVoice.getName());
                            break; // Use the first suitable male voice found
                        }
                    }
                } else {
                    Log.w(TAG, "TTS: No voices returned by getVoices().");
                }

                // Set the selected voice if found
                if (selectedVoice != null) {
                    int voiceResult = tts.setVoice(selectedVoice);
                    if (voiceResult == TextToSpeech.SUCCESS) {
                        Log.i(TAG, "TTS: Successfully set male voice: " + selectedVoice.getName());
                        ttsInitialized = true;
                    } else {
                        Log.e(TAG, "TTS: Failed to set selected male voice. Result code: " + voiceResult);
                        ttsInitialized = true; // Still initialized, using default voice
                    }
                } else {
                    Log.w(TAG, "TTS: No suitable male voice found for locale " + targetLocale + ". Using default voice.");
                    ttsInitialized = true; // Initialized, using the default voice
                }

            } catch (Exception e) {
                Log.e(TAG, "TTS: Error during voice selection.", e);
                ttsInitialized = true; // Still initialized, using default voice
            }
        } else {
            // --- Fallback for API < 21 ---
            Log.w(TAG, "TTS: Voice selection not available on API level " + Build.VERSION.SDK_INT + ". Using default voice for locale " + targetLocale);
            ttsInitialized = true; // Language was set, usable with default voice
        }

    } else {
        Log.e(TAG, "TTS Initialization Failed! Status: " + status);
        Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_SHORT).show();
        ttsInitialized = false;
    }
}

// --- Helper method to speak text ---
private void speakText(String textToSpeak) {
    if (!ttsInitialized || tts == null) {
        Log.e(TAG, "TTS not initialized, cannot speak.");
        return;
    }
    if (textToSpeak == null || textToSpeak.isEmpty()) {
        Log.w(TAG, "Attempted to speak empty text.");
        return;
    }

    String utteranceId = this.hashCode() + ":" + textToSpeak;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    } else {
        //noinspection deprecation
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null);
    }
    Log.d(TAG, "TTS speaking: \"" + textToSpeak + "\" (Utterance ID: " + utteranceId + ")");
}

// --- Calibration Methods ---
private void loadCalibration() {
    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    calibratedVolumeRms = prefs.getFloat(PREF_CALIBRATION_RMS, -1.0f);
    Log.i(TAG, "Loaded calibration RMS: " + calibratedVolumeRms);
}

private void saveCalibration(double rmsValue) {
    calibratedVolumeRms = rmsValue;
    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    SharedPreferences.Editor editor = prefs.edit();
    editor.putFloat(PREF_CALIBRATION_RMS, (float) rmsValue);
    editor.apply();
    Log.i(TAG, "Saved calibration RMS: " + rmsValue);
}

private void startCalibrationSafe() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        setErrorState(getString(R.string.error_permission_denied) + ": Microphone needed for calibration.");
    } else {
        startCalibration();
    }
}

@SuppressLint("MissingPermission")
private void startCalibration() {
    if (currentState != STATE_READY && currentState != STATE_DONE && currentState != STATE_ERROR) {
        Toast.makeText(this, "Cannot calibrate now (State: " + stateToString(currentState) + ")", Toast.LENGTH_SHORT).show();
        return;
    }
    if (speechService != null) {
        Toast.makeText(this, "Stop listening first", Toast.LENGTH_SHORT).show();
        return;
    }
    Log.d(TAG, "Starting calibration process...");
    setUiState(STATE_CALIBRATING);
    calibrationExecutor.execute(() -> {
        AudioRecord calibrationAudioRecord = null;
        List<Double> rmsValues = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        int bufferSize;
        try {
            bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING);
            if (bufferSize <= 0) {
                Log.w(TAG, "getMinBufferSize returned <= 0, using default.");
                bufferSize = AUDIO_SAMPLE_RATE * 2 * CALIBRATION_BUFFER_SIZE_FACTOR;
            } else {
                bufferSize *= CALIBRATION_BUFFER_SIZE_FACTOR;
            }
            Log.d(TAG, "Calibration buffer size: " + bufferSize);

            calibrationAudioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING, bufferSize);

            if (calibrationAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord init failed. State: " + calibrationAudioRecord.getState());
            }

            calibrationAudioRecord.startRecording();
            if (calibrationAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("AudioRecord failed to start recording. State: " + calibrationAudioRecord.getRecordingState());
            }

            short[] audioBuffer = new short[bufferSize / 2];
            Log.d(TAG, "Calibration started. Reading audio...");

            while (currentState == STATE_CALIBRATING && (System.currentTimeMillis() - startTime < CALIBRATION_DURATION_MS)) {
                int shortsRead = calibrationAudioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (shortsRead > 0) {
                    double rms = calculateRms(audioBuffer, shortsRead);
                    if (rms > 10) {
                        rmsValues.add(rms);
                    }
                } else if (shortsRead < 0) {
                    Log.e(TAG, "AudioRecord read error: " + shortsRead);
                }
            }
            Log.d(TAG, "Calibration audio reading finished.");

            if (calibrationAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                calibrationAudioRecord.stop();
            }

            if (!rmsValues.isEmpty()) {
                double sumRms = 0;
                for (Double rms : rmsValues) sumRms += rms;
                final double avgRms = sumRms / rmsValues.size();
                saveCalibration(avgRms);
                Log.i(TAG, "Calibration complete. Avg RMS: " + avgRms + " from " + rmsValues.size() + " samples.");
                mainHandler.post(() -> {
                    Toast.makeText(VoskActivity.this, String.format(getString(R.string.calibration_complete), avgRms), Toast.LENGTH_LONG).show();
                    setUiState(STATE_READY);
                });
            } else {
                Log.w(TAG, "Calibration: No significant audio detected.");
                saveCalibration(-1.0);
                mainHandler.post(() -> {
                    Toast.makeText(VoskActivity.this, R.string.calibration_failed_no_audio, Toast.LENGTH_LONG).show();
                    setUiState(STATE_READY);
                });
            }
        } catch (final Exception e) {
            Log.e(TAG, "Calibration failed", e);
            saveCalibration(-1.0);
            mainHandler.post(() -> setErrorState(String.format(getString(R.string.calibration_failed_error), e.getMessage())));
        } finally {
            if (calibrationAudioRecord != null) {
                if (calibrationAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    try {
                        if(calibrationAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            calibrationAudioRecord.stop();
                        }
                    } catch (IllegalStateException ignored) {
                        Log.e(TAG, "Calibration: IllegalStateException on stop().");
                    } finally {
                        calibrationAudioRecord.release();
                    }
                }
                calibrationAudioRecord = null;
                Log.d(TAG, "Calibration AudioRecord released.");
            }
            if (currentState == STATE_CALIBRATING) {
                 mainHandler.post(() -> { if (currentState == STATE_CALIBRATING) setUiState(STATE_READY); });
            }
        }
    });
}

private double calculateRms(short[] audioData, int length) {
    if (length <= 0) return 0.0;
    double sumSquare = 0.0;
    for (int i = 0; i < length; i++) {
        double sample = audioData[i] / 32768.0;
        sumSquare += sample * sample;
    }
    return Math.sqrt(sumSquare / length) * 1000;
}

// --- Permission Handling ---
@Override
public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initModel();
        } else {
            Log.e(TAG, "Audio recording permission denied by user.");
            setErrorState(getString(R.string.error_permission_denied) + ": Microphone access is required.");
        }
    }
}

// --- Model Initialization ---
private void initModel() {
    if (model != null || (currentState != STATE_START && currentState != STATE_ERROR) ) {
        Log.d(TAG, "initModel called but model exists or state is not START/ERROR. Current state: " + stateToString(currentState));
        if (model != null && currentState != STATE_MIC && currentState != STATE_CALIBRATING) {
            setUiState(STATE_READY);
        }
        return;
    }

    Log.d(TAG, "Initializing model...");
    if (currentState != STATE_ERROR) {
         setUiState(STATE_START);
    }

    StorageService.unpack(this, "model-en-us", "model",
            (model) -> {
                this.model = model;
                Log.i(TAG, "Model unpacked and loaded successfully.");
                mainHandler.post(() -> setUiState(STATE_READY));
            },
            (exception) -> {
                Log.e(TAG, "Model unpacking failed", exception);
                setErrorState("Failed to unpack/load the model: " + exception.getMessage());
                this.model = null;
            });
}

// --- Microphone Recognition Control ---
private void recognizeMicrophone() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "Recognize microphone requested without permission.");
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        return;
    }

    if (speechService != null) {
        // --- Stop Listening ---
        Log.d(TAG, "Stopping microphone recognition.");
        setUiState(STATE_DONE); // Update UI first
        speechService.stop();
        speechService.shutdown();
        speechService = null;
        isPaused = false; // Reset pause state when stopping completely
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
            Log.d(TAG, "TTS stopped manually on recognition stop.");
        }
    } else {
        // --- Start Listening ---
        if (model == null) {
            Log.e(TAG, "Recognize microphone requested but model is not loaded.");
            setErrorState(getString(R.string.error_model_load));
            initModel(); // Attempt to load model if missing
            return;
        }
        if (calibratedVolumeRms == -1.0) {
            Toast.makeText(this, R.string.calibrate_tip, Toast.LENGTH_LONG).show();
        }
        Log.d(TAG, "Starting microphone recognition.");
        try {
            Recognizer rec = new Recognizer(model, (float) AUDIO_SAMPLE_RATE);
            speechService = new SpeechService(rec, (float) AUDIO_SAMPLE_RATE);
            isPaused = false; // Ensure pause state is false when starting
            speechService.startListening(this);
            setUiState(STATE_MIC);
            if (jarvisResponseView != null) jarvisResponseView.setText(R.string.listening_status_empty);
        } catch (Exception e) {
            Log.e(TAG, "RecognizeMicrophone Start Error", e);
            setErrorState(getString(R.string.error_mic_init) + e.getMessage());
            if (speechService != null) {
                speechService.stop();
                speechService.shutdown();
                speechService = null;
            }
            setUiState(STATE_ERROR);
        }
    }
}

// --- RecognitionListener Implementation ---
@Override
public void onResult(String hypothesis) {
    // Log.v(TAG, "onResult (Partial/Intermediate): " + hypothesis);
}

@Override
public void onFinalResult(String hypothesis) {
    Log.i(TAG, "onFinalResult Raw: " + hypothesis);
    final String extractedText = extractTextFromHypothesis(hypothesis, "text");
    Log.i(TAG, "onFinalResult Extracted: \"" + extractedText + "\"");

    mainHandler.post(() -> {
        if (currentState != STATE_MIC && currentState != STATE_DONE) {
            Log.w(TAG, "onFinalResult received but state is not MIC or DONE. State: " + stateToString(currentState));
            return;
        }

        if (resultView != null && !extractedText.isEmpty()) {
            resultView.append("\n" + getString(R.string.final_result_prefix) + extractedText);
            scrollToBottom(resultView);
        } else if (resultView != null && extractedText.isEmpty()) {
             Log.d(TAG, "Empty final result.");
             // resultView.append("\n" + getString(R.string.empty_final_result));
             // scrollToBottom(resultView);
        }

        // Continue listening unless explicitly stopped or timeout/error occurs.
        if (currentState == STATE_DONE && speechService == null) {
             Log.d(TAG,"Final result received after service stopped.");
             setUiState(STATE_DONE);
         } else if (currentState == STATE_MIC) {
             Log.d(TAG, "Final result received, continuing listening (MIC state).");
             // Clear Jarvis response for next utterance? Optional.
             // if (jarvisResponseView != null) jarvisResponseView.setText(R.string.listening_status_empty);
         }
    });
}

@Override
public void onPartialResult(String hypothesis) {
    final String partialText = extractTextFromHypothesis(hypothesis, "partial");
    if (partialText == null || partialText.isEmpty()) {
        return;
    }

    mainHandler.post(() -> {
        if (currentState == STATE_MIC && !isPaused) { // <<< Check !isPaused here
            String lowerHypothesis = partialText.toLowerCase().trim();
            String resp = null;
            boolean commandMatched = false;

            // --- Command Checking Logic ---
            if (!commandMatched && (lowerHypothesis.contains("hello") || lowerHypothesis.contains("hallo")) && (lowerHypothesis.contains("jarvis") || lowerHypothesis.contains("charlie") || lowerHypothesis.contains("java"))) {
                Log.d(TAG, "Partial CMD Matched: Hello Jarvis");
                resp = getString(R.string.response_hello_sir);
                commandMatched = true;
            }
            if (!commandMatched && (lowerHypothesis.endsWith("jarvis") || lowerHypothesis.endsWith("charlie") || lowerHypothesis.endsWith("java")) && lowerHypothesis.length() < 15) {
                 Log.d(TAG, "Partial CMD Matched: Jarvis Wake Word");
                 resp = getString(R.string.response_yes_sir);
                 commandMatched = true;
            }
            if (!commandMatched && (lowerHypothesis.contains("play music") || lowerHypothesis.contains("pause music") || lowerHypothesis.contains("stop music") || lowerHypothesis.contains("toggle music") || lowerHypothesis.equals("pause") || lowerHypothesis.equals("play") )) {
                Log.d(TAG, "Partial CMD Matched: Play/Pause Music");
                resp = getString(R.string.response_play_music);
                commandMatched = true;
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
            if (!commandMatched && (lowerHypothesis.contains("next song") || lowerHypothesis.contains("next track") || lowerHypothesis.equals("next"))) {
                Log.d(TAG, "Partial CMD Matched: Next Song");
                resp = getString(R.string.response_next_song);
                commandMatched = true;
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
            }
            if (!commandMatched && (lowerHypothesis.contains("previous song") || lowerHypothesis.contains("last song") || lowerHypothesis.contains("previous track") || lowerHypothesis.contains("go back") || lowerHypothesis.equals("previous") || lowerHypothesis.equals("back"))) {
                 Log.d(TAG, "Partial CMD Matched: Previous Song");
                 resp = getString(R.string.response_previous_song);
                 commandMatched = true;
                 sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
             }
            // --- End Command Checking ---

            if (commandMatched && resp != null) {
                Log.i(TAG, "Command Matched: \"" + partialText + "\" -> Response: \"" + resp + "\"");
                if (jarvisResponseView != null) {
                    jarvisResponseView.setText(resp);
                }
                speakText(resp); // Speak the response

                if (resultView != null) {
                    resultView.append("\nCMD: " + partialText);
                    scrollToBottom(resultView);
                }
            } else if (jarvisResponseView != null) {
                // Only update listening status if no command matched
                jarvisResponseView.setText(getString(R.string.listening_status, partialText));
            }
        } else if (currentState == STATE_MIC && isPaused) {
            Log.v(TAG, "Partial result received while paused, ignoring: " + partialText);
        } else {
             Log.w(TAG, "onPartialResult received but state is not MIC. State: " + stateToString(currentState));
        }
    });
}

@Override
public void onError(final Exception e) {
    Log.e(TAG, "Recognition Error", e);
    mainHandler.post(() -> {
        setErrorState(getString(R.string.error_recognizer) + e.getMessage());
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
            isPaused = false; // Reset pause on error
        }
    });
}

@Override
public void onTimeout() {
    Log.w(TAG, "Recognition Timeout (Silence Detected)");
    mainHandler.post(() -> {
        if (currentState == STATE_MIC && speechService != null) {
             if (resultView != null) {
                resultView.append("\n" + getString(R.string.timeout_message));
                scrollToBottom(resultView);
            }
            if (jarvisResponseView != null && jarvisResponseView.getText().toString().startsWith(getString(R.string.listening_status_prefix))) {
                jarvisResponseView.setText(R.string.listening_status_empty);
            }
            Log.d(TAG,"Timeout detected, continuing listening (MIC state).");
            // Option: Stop listening on timeout
            // recognizeMicrophone();
            // setUiState(STATE_DONE);
        }
    });
}

// --- Media Key Control ---
private void sendMediaKeyEvent(int keyCode) {
    if (audioManager == null) {
        Log.e(TAG, "AudioManager not initialized. Cannot send media key.");
        return;
    }
    Log.d(TAG, "Dispatching media key event: " + KeyEvent.keyCodeToString(keyCode));
    KeyEvent downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
    audioManager.dispatchMediaKeyEvent(downEvent);
    KeyEvent upEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
    audioManager.dispatchMediaKeyEvent(upEvent);
}

// --- UI State Management --- MODIFIED ---
private void setUiState(int state) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainHandler.post(() -> setUiState(state));
        return;
    }
    if (this.currentState == state) return; // No change

    Log.d(TAG, "UI State Transition: " + stateToString(this.currentState) + " -> " + stateToString(state));
    this.currentState = state;

    cB = findViewById(R.id.calibrate_button);
    rMB = findViewById(R.id.recognize_mic);
    pB = findViewById(R.id.pause); // Assuming pB is the ToggleButton

    TextView currentJarvisResponseView = findViewById(R.id.jarvis_response_text);
    if (currentJarvisResponseView != null) {
        switch (state) {
            case STATE_START: currentJarvisResponseView.setText(R.string.preparing); break;
            case STATE_READY:
                CharSequence currentText = currentJarvisResponseView.getText();
                if (currentText.equals(getString(R.string.preparing)) ||
                    currentText.toString().startsWith(getString(R.string.error_prefix)) ||
                    currentText.equals(getString(R.string.calibrating)) ||
                    currentText.equals(getString(R.string.paused_status))) { // Clear paused status too
                    currentJarvisResponseView.setText("");
                }
                break;
            case STATE_CALIBRATING: currentJarvisResponseView.setText(R.string.calibrating); break;
            case STATE_MIC:
                // Set text based on whether we are entering MIC state paused or not
                currentJarvisResponseView.setText(isPaused ? R.string.paused_status : R.string.listening_status_empty);
                break;
            case STATE_DONE:
                if (currentJarvisResponseView.getText().toString().startsWith(getString(R.string.listening_status_prefix)) ||
                    currentJarvisResponseView.getText().equals(getString(R.string.paused_status))) {
                    currentJarvisResponseView.setText("");
                }
                break;
            case STATE_ERROR: currentJarvisResponseView.setText(""); break;
        }
    }

    // --- Button Enable/Disable Logic ---
    boolean isModelReady = (model != null);
    boolean canCalibrate = (state == STATE_READY || state == STATE_DONE || state == STATE_ERROR) && speechService == null;
    boolean canListen = (state == STATE_READY || state == STATE_DONE || state == STATE_ERROR) && isModelReady;
    boolean isListening = (state == STATE_MIC);
    boolean canPause = isListening; // Can only pause/resume if actively listening (in MIC state)

    if (cB != null) cB.setEnabled(canCalibrate);
    if (rMB != null) {
        rMB.setEnabled(canListen || isListening);
        rMB.setText(isListening ? R.string.stop_microphone : R.string.recognize_microphone);
    }
    if (pB instanceof ToggleButton) {
        ToggleButton togglePauseButton = (ToggleButton) pB;
        togglePauseButton.setEnabled(canPause);
        // Set checked state based on our local isPaused variable ONLY when enabled
        if (canPause) {
            togglePauseButton.setChecked(this.isPaused); // <<< Use local isPaused state
        } else {
            togglePauseButton.setChecked(false); // Ensure unchecked when disabled
        }
    } else if (pB != null) {
        pB.setEnabled(canPause);
    }
}


private String stateToString(int state) {
    switch (state) {
        case STATE_START: return "START"; case STATE_READY: return "READY";
        case STATE_CALIBRATING: return "CALIBRATING"; case STATE_MIC: return "MIC";
        case STATE_DONE: return "DONE"; case STATE_ERROR: return "ERROR";
        default: return "UNKNOWN(" + state + ")";
    }
}

private void setErrorState(String message) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainHandler.post(() -> setErrorState(message));
        return;
    }
    Log.e(TAG, "Error State Set: " + message);
    if (resultView != null) {
        resultView.setText(getString(R.string.recognition_log_title) + "\n" + getString(R.string.error_prefix) + message);
        scrollToBottom(resultView);
    }
    setUiState(STATE_ERROR);
}

// --- Pause Handling --- MODIFIED ---
private void pause(boolean checked) {
    // This method is called by the ToggleButton's OnCheckedChangeListener
    Log.d(TAG, "Pause Toggled via Button: " + checked);

    if (speechService != null && currentState == STATE_MIC) {
        this.isPaused = checked; // <<< Update local state FIRST
        speechService.setPause(this.isPaused); // Tell the service the new state
        Log.i(TAG,"SpeechService pause set to: " + this.isPaused);

        // Update UI text immediately based on the new local state
        if (jarvisResponseView != null) {
            jarvisResponseView.setText(this.isPaused ? R.string.paused_status : R.string.listening_status_empty);
        }

        // Stop TTS if pausing
        if (this.isPaused && tts != null && tts.isSpeaking()) {
            tts.stop();
            Log.d(TAG, "TTS stopped due to recognition pause.");
        }
         // Ensure the button visually reflects the state (might be redundant if setUiState is called, but safe)
         if (pauseButton != null) {
             pauseButton.setChecked(this.isPaused);
         }

    } else {
        // If service is null or not in MIC state, the button shouldn't be enabled,
        // but if it somehow gets called, log it and potentially reset visual state.
        Log.w(TAG,"Pause toggled but service not running or not in MIC state.");
        this.isPaused = false; // Ensure local state is not paused
         if (pauseButton != null) {
             pauseButton.setChecked(false); // Reset button visual state
         }
    }
    // We DO NOT call setUiState here because the overall *Activity* state (STATE_MIC) hasn't changed.
    // Only the internal pause status has changed, which we handle directly.
}


// --- Activity Lifecycle ---
@Override
protected void onResume() {
    super.onResume();
    Log.d(TAG, "onResume - Current State: " + stateToString(currentState));
    if (currentState == STATE_ERROR && model != null) {
        Log.d(TAG, "onResume: Recovering from error state to READY.");
        setUiState(STATE_READY);
    } else if (currentState == STATE_START && model == null) {
         if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
             Log.d(TAG, "onResume: Permission granted, attempting model init.");
             initModel();
         }
    }
    // Reset pause state on resume? Optional, depends on desired behavior.
    // isPaused = false;
    // if (pauseButton != null) pauseButton.setChecked(false);
}

@Override
protected void onPause() {
    super.onPause();
    Log.d(TAG, "onPause - Current State: " + stateToString(currentState));
    if (speechService != null) {
        Log.i(TAG, "onPause: Stopping active speech service.");
        speechService.stop();
        speechService.shutdown();
        speechService = null;
        isPaused = false; // Reset pause state when stopping
        // Update UI state *before* pause completes fully
        setUiState(STATE_DONE);
    }
}

@Override
protected void onDestroy() {
    super.onDestroy();
    Log.d(TAG, "onDestroy - Shutting down resources.");

    if (tts != null) {
        Log.d(TAG, "Shutting down TTS engine...");
        tts.stop();
        tts.shutdown();
        tts = null;
        Log.i(TAG, "TTS Shutdown complete.");
    }

    if (speechService != null) {
        Log.w(TAG, "onDestroy: SpeechService was not null, shutting down now.");
        speechService.stop();
        speechService.shutdown();
        speechService = null;
    }

    Log.d(TAG,"Shutting down calibration executor...");
    calibrationExecutor.shutdownNow();
    try {
        if (!calibrationExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Calibration executor did not terminate in time.");
        } else {
            Log.d(TAG,"Calibration executor terminated.");
        }
    } catch (InterruptedException e) {
         Log.e(TAG,"Interrupted while waiting for calibration executor termination.", e);
        Thread.currentThread().interrupt();
    }

    if (model != null) {
         Log.d(TAG,"Setting model reference to null.");
         model = null;
    }

    if (mainHandler != null) {
        Log.d(TAG,"Removing handler callbacks.");
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler = null;
    }

    Log.i(TAG, "onDestroy finished.");
}


// --- Helper Methods ---
private void scrollToBottom(final TextView tv) {
    if (tv == null || tv.getLayout() == null) return;
    mainHandler.post(() -> {
        final Layout layout = tv.getLayout();
        if (layout != null) {
            final int scrollAmount = layout.getLineTop(tv.getLineCount()) - tv.getHeight();
            if (scrollAmount > 0) {
                tv.scrollTo(0, scrollAmount);
            } else {
                tv.scrollTo(0, 0);
            }
        }
    });
}

private String extractTextFromHypothesis(String hypothesis, String preferredKey) {
    if (hypothesis == null || hypothesis.trim().isEmpty()) return "";
    String trimmedHypothesis = hypothesis.trim();

    if (trimmedHypothesis.startsWith("{") && trimmedHypothesis.endsWith("}")) {
        try {
            JSONObject json = new JSONObject(trimmedHypothesis);
            if (json.has(preferredKey)) {
                String text = json.getString(preferredKey);
                if (text != null && !text.trim().isEmpty()) return text.trim();
            }
            String fallbackKey = preferredKey.equals("text") ? "partial" : "text";
            if (json.has(fallbackKey)) {
                String fallbackText = json.getString(fallbackKey);
                if (fallbackText != null && !fallbackText.trim().isEmpty()) {
                    Log.v(TAG, "Extracted text using fallback key '" + fallbackKey + "'");
                    return fallbackText.trim();
                }
            }
            if (json.has("result")) {
                 String resultText = json.get("result").toString();
                 if (resultText != null && !resultText.trim().isEmpty() && !resultText.equals("[]")) {
                     Log.v(TAG, "Extracted text using 'result' key");
                     return resultText.trim();
                 }
             }
            Log.w(TAG, "Could not find '" + preferredKey + "' or fallback key in JSON: " + trimmedHypothesis);
            return "";
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse JSON hypothesis: " + trimmedHypothesis, e);
            return "";
        }
    } else {
        Log.w(TAG, "Hypothesis is not JSON, returning raw: " + trimmedHypothesis);
        return trimmedHypothesis;
    }
}

// --- Required String Resources (Ensure these are in res/values/strings.xml) ---
/*
<resources>
    <string name="app_name">Vosk Demo</string>

    // Statuses
    <string name="preparing">Preparing…</string>
    <string name="ready">Ready</string>
    <string name="calibrating">Calibrating… Please remain silent.</string>
    <string name="listening_status">Listening: %s...</string>
    <string name="listening_status_prefix">Listening:</string> // Used for internal checks
    <string name="listening_status_empty">Listening...</string>
    <string name="paused_status">Paused</string>
    <string name="recognition_log_title">Recognition Results Log</string>

    // Buttons
    <string name="recognize_microphone">Start Listening</string>
    <string name="stop_microphone">Stop Listening</string>
    <string name="pause_caption_on">Pause</string> <!-- Text for ToggleButton when OFF -->
    <string name="pause_caption_off">Resume</string> <!-- Text for ToggleButton when ON (Checked) -->
    <string name="calibrate_button_text">Calibrate</string>

    // Results
    <string name="final_result_prefix">Final: </string>
    <string name="empty_final_result">[Silent/Empty]</string>
    <string name="timeout_message">[Timeout]</string>

    // Jarvis Responses
    <string name="response_hello_sir">Hello Sir</string>
    <string name="response_yes_sir">Yes Sir?</string>
    <string name="response_play_music">Toggling Music Playback…</string>
    <string name="response_next_song">Playing next song…</string>
    <string name="response_previous_song">Playing previous song…</string>

    // Errors and Tips
    <string name="error_prefix">ERROR: </string>
    <string name="error_permission_denied">Permission denied</string>
    <string name="error_model_load">Failed to load speech model.</string>
    <string name="error_mic_init">Failed to initialize microphone: </string>
    <string name="error_recognizer">Recognizer error: </string>
    <string name="calibrate_tip">Tip: Calibrate first for better results in noise.</string>
    <string name="calibration_complete">Calibration complete. Average RMS: %.2f</string>
    <string name="calibration_failed_no_audio">Calibration failed: No significant audio detected.</string>
    <string name="calibration_failed_error">Calibration failed: %s</string>

</resources>
*/


} // End of VoskActivity class
