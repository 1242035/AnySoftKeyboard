/*
 * Copyright (c) 2015 Menny Even-Danan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.anysoftkeyboard;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import com.anysoftkeyboard.LayoutSwitchAnimationListener.AnimationType;
import com.anysoftkeyboard.api.KeyCodes;
import com.anysoftkeyboard.base.dictionaries.Dictionary;
import com.anysoftkeyboard.base.dictionaries.WordComposer;
import com.anysoftkeyboard.devicespecific.Clipboard;
import com.anysoftkeyboard.dictionaries.DictionaryAddOnAndBuilder;
import com.anysoftkeyboard.dictionaries.ExternalDictionaryFactory;
import com.anysoftkeyboard.dictionaries.Suggest;
import com.anysoftkeyboard.dictionaries.TextEntryState;
import com.anysoftkeyboard.dictionaries.sqlite.AutoDictionary;
import com.anysoftkeyboard.keyboards.AnyKeyboard;
import com.anysoftkeyboard.keyboards.AnyKeyboard.HardKeyboardTranslator;
import com.anysoftkeyboard.keyboards.CondenseType;
import com.anysoftkeyboard.keyboards.Keyboard.Key;
import com.anysoftkeyboard.keyboards.KeyboardAddOnAndBuilder;
import com.anysoftkeyboard.keyboards.KeyboardSwitcher;
import com.anysoftkeyboard.keyboards.KeyboardSwitcher.NextKeyboardType;
import com.anysoftkeyboard.keyboards.physical.HardKeyboardActionImpl;
import com.anysoftkeyboard.keyboards.physical.MyMetaKeyKeyListener;
import com.anysoftkeyboard.keyboards.views.AnyKeyboardView;
import com.anysoftkeyboard.keyboards.views.CandidateView;
import com.anysoftkeyboard.keyboards.views.OnKeyboardActionListener;
import com.anysoftkeyboard.quicktextkeys.QuickTextKey;
import com.anysoftkeyboard.quicktextkeys.QuickTextKeyFactory;
import com.anysoftkeyboard.receivers.PackagesChangedReceiver;
import com.anysoftkeyboard.receivers.SoundPreferencesChangedReceiver;
import com.anysoftkeyboard.receivers.SoundPreferencesChangedReceiver.SoundPreferencesChangedListener;
import com.anysoftkeyboard.theme.KeyboardTheme;
import com.anysoftkeyboard.theme.KeyboardThemeFactory;
import com.anysoftkeyboard.ui.VoiceInputNotInstalledActivity;
import com.anysoftkeyboard.ui.dev.DeveloperUtils;
import com.anysoftkeyboard.ui.settings.MainSettingsActivity;
import com.anysoftkeyboard.base.utils.GCUtils;
import com.anysoftkeyboard.base.utils.GCUtils.MemRelatedOperation;
import com.anysoftkeyboard.utils.ChewbaccaOnTheDrums;
import com.anysoftkeyboard.utils.Log;
import com.anysoftkeyboard.utils.ModifierKeyState;
import com.anysoftkeyboard.utils.Workarounds;
import com.google.android.voiceime.VoiceRecognitionTrigger;
import com.menny.android.anysoftkeyboard.AnyApplication;
import com.menny.android.anysoftkeyboard.BuildConfig;
import com.menny.android.anysoftkeyboard.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Input method implementation for QWERTY-ish keyboard.
 */
public abstract class AnySoftKeyboard extends InputMethodService implements
        OnKeyboardActionListener, OnSharedPreferenceChangeListener,
        AnyKeyboardContextProvider, SoundPreferencesChangedListener {

    private final static String TAG = "ASK";
    private static final long MINIMUM_REFRESH_TIME_FOR_DICTIONARIES = 30 * 1000;
    private static final long ONE_FRAME_DELAY = 1000L / 60L;
    private static final long CLOSE_DICTIONARIES_DELAY = 5 * ONE_FRAME_DELAY;
    private static final ExtractedTextRequest EXTRACTED_TEXT_REQUEST = new ExtractedTextRequest();

    private final AskPrefs mAskPrefs;
    private final ModifierKeyState mShiftKeyState = new ModifierKeyState(true/*supports locked state*/);
    private final ModifierKeyState mControlKeyState = new ModifierKeyState(false/*does not support locked state*/);
    private final HardKeyboardActionImpl mHardKeyboardAction = new HardKeyboardActionImpl();
    private final KeyboardUIStateHandler mKeyboardHandler = new KeyboardUIStateHandler(this);

    // receive ringer mode changes to detect silent mode
    private final SoundPreferencesChangedReceiver mSoundPreferencesChangedReceiver = new SoundPreferencesChangedReceiver(this);
    private final PackagesChangedReceiver mPackagesChangedReceiver = new PackagesChangedReceiver(this);
    protected IBinder mImeToken = null;
    private KeyboardSwitcher mKeyboardSwitcher;
    /*package*/ TextView mCandidateCloseText;
    private SharedPreferences mPrefs;
    private LayoutSwitchAnimationListener mSwitchAnimator;
    private boolean mDistinctMultiTouch = true;
    private AnyKeyboardView mInputView;
    private View mCandidatesParent;
    private CandidateView mCandidateView;
    private long mLastDictionaryRefresh = -1;
    private Suggest mSuggest;
    private CompletionInfo[] mCompletions;
    private AlertDialog mOptionsDialog;
    private long mMetaState;
    @NonNull
    private final SparseBooleanArray mSentenceSeparators = new SparseBooleanArray();

    private AutoDictionary mAutoDictionary;
    private WordComposer mWord = new WordComposer();

    private int mFirstDownKeyCode;

    private static final long MAX_TIME_TO_EXPECT_SELECTION_UPDATE = 1500;
    private long mExpectingSelectionUpdateBy = Long.MIN_VALUE;
    private int mOrientation = Configuration.ORIENTATION_PORTRAIT;
    private int mCommittedLength;
    private CharSequence mCommittedWord = "";
    private int mGlobalCursorPosition = 0;
    private int mGlobalSelectionStartPosition = 0;

    private boolean mArrowSelectionState;

    private int mLastEditorIdPhysicalKeyboardWasUsed = 0;
    /*
     * Do we do prediction now
     */
    private boolean mPredicting;
    /*
     * is prediction needed for the current input connection
     */
    private boolean mPredictionOn;
    /*
     * is out-side completions needed
     */
    private boolean mCompletionOn;
    private boolean mAutoSpace;
    private boolean mAutoCorrectOn;
    private boolean mAllowSuggestionsRestart = true;
    private boolean mCurrentlyAllowSuggestionRestart = true;
    private boolean mJustAutoAddedWord = false;
    private boolean mDoNotFlipQuickTextKeyAndPopupFunctionality;
    private String mOverrideQuickTextText = null;
    private boolean mAutoCap;
    private boolean mQuickFixes;
    /*
     * Configuration flag. Should we support dictionary suggestions
     */
    private boolean mShowSuggestions = false;
    private boolean mAutoComplete;

    private boolean mShowKeyboardIconInStatusBar;

    private static final int UNDO_COMMIT_NONE = -1;
    private static final int UNDO_COMMIT_WAITING_TO_RECORD_POSITION = -2;
    /*
     * This will help us find out if UNDO_COMMIT is still possible to be done
     */
    private int mUndoCommitCursorPosition = UNDO_COMMIT_NONE;
    private AudioManager mAudioManager;
    private boolean mSilentMode;
    private boolean mSoundOn;
    // between 0..100. This is the custom volume
    private int mSoundVolume;
    private Vibrator mVibrator;
    private int mVibrationDuration;
    private CondenseType mKeyboardInCondensedMode = CondenseType.None;
    private boolean mJustAddedAutoSpace;
    private CharSequence mJustAddOnText = null;
    private boolean mLastCharacterWasShifted = false;
    private InputMethodManager mInputMethodManager;
    private VoiceRecognitionTrigger mVoiceRecognitionTrigger;
    //a year ago.
    private static final long NEVER_TIME_STAMP = (-1L) * (365L * 24L * 60L * 60L * 1000L);
    private long mLastSpaceTimeStamp = NEVER_TIME_STAMP;

    public AnySoftKeyboard() {
        mAskPrefs = AnyApplication.getConfig();
    }

    //TODO SHOULD NOT USE THIS METHOD AT ALL!
    private static int getCursorPosition(@Nullable InputConnection connection) {
        if (connection == null)
            return 0;
        ExtractedText extracted = connection.getExtractedText(EXTRACTED_TEXT_REQUEST, 0);
        if (extracted == null)
            return 0;
        return extracted.startOffset + extracted.selectionStart;
    }

    private static boolean isBackWordStopChar(int c) {
        return !Character.isLetter(c);
    }

    private static String getDictionaryOverrideKey(AnyKeyboard currentKeyboard) {
        return currentKeyboard.getKeyboardPrefId() + "_override_dictionary";
    }

    @Override
    @NonNull
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return new InputMethodImpl() {
            @Override
            public void attachToken(IBinder token) {
                super.attachToken(token);
                mImeToken = token;
            }
        };
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mOrientation = getResources().getConfiguration().orientation;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if ((!BuildConfig.DEBUG) && DeveloperUtils.hasTracingRequested(getApplicationContext())) {
            try {
                DeveloperUtils.startTracing();
                Toast.makeText(getApplicationContext(),
                        R.string.debug_tracing_starting, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                //see issue https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/105
                //I might get a "Permission denied" error.
                e.printStackTrace();
                Toast.makeText(getApplicationContext(),
                        R.string.debug_tracing_starting_failed, Toast.LENGTH_LONG).show();
            }
        }
        Log.i(TAG, "****** AnySoftKeyboard v%s (%d) service started.", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        if (!BuildConfig.DEBUG && BuildConfig.VERSION_NAME.endsWith("-SNAPSHOT"))
            throw new RuntimeException("You can not run a 'RELEASE' build with a SNAPSHOT postfix!");

        if (mAskPrefs.getAnimationsLevel() != AskPrefs.AnimationsLevel.None) {
            final int fancyAnimation = getResources().getIdentifier("Animation_InputMethodFancy", "style", "android");
            if (fancyAnimation != 0) {
                Log.i(TAG, "Found Animation_InputMethodFancy as %d, so I'll use this", fancyAnimation);
                getWindow().getWindow().setWindowAnimations(fancyAnimation);
            } else {
                Log.w(TAG, "Could not find Animation_InputMethodFancy, using default animation");
                getWindow().getWindow().setWindowAnimations(android.R.style.Animation_InputMethod);
            }
        }

        mInputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        updateRingerMode();
        // register to receive ringer mode changes for silent mode
        registerReceiver(mSoundPreferencesChangedReceiver, mSoundPreferencesChangedReceiver.createFilterToRegisterOn());
        // register to receive packages changes
        registerReceiver(mPackagesChangedReceiver, mPackagesChangedReceiver.createFilterToRegisterOn());
        mVibrator = ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE));

        mSuggest = createSuggest();
        mKeyboardSwitcher = createKeyboardSwitcher();

        loadSettings();
        mAskPrefs.addChangedListener(this);

        mVoiceRecognitionTrigger = new VoiceRecognitionTrigger(this);

        mSwitchAnimator = new LayoutSwitchAnimationListener(this);
    }

    @NonNull
    protected KeyboardSwitcher createKeyboardSwitcher() {
        return new KeyboardSwitcher(this);
    }

    @NonNull
    protected Suggest createSuggest() {
        return new Suggest(this);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "AnySoftKeyboard has been destroyed! Cleaning resources..");
        mSwitchAnimator.onDestroy();
        mKeyboardHandler.removeAllMessages();
        mAskPrefs.removeChangedListener(this);

        unregisterReceiver(mSoundPreferencesChangedReceiver);
        unregisterReceiver(mPackagesChangedReceiver);

        mInputMethodManager.hideStatusIcon(mImeToken);

        hideWindow();

        if (mInputView != null) mInputView.onViewNotRequired();
        mInputView = null;

        closeDictionaries();

        if (DeveloperUtils.hasTracingStarted()) {
            DeveloperUtils.stopTracing();
            Toast.makeText(
                    getApplicationContext(),
                    getString(R.string.debug_tracing_finished,
                            DeveloperUtils.getTraceFile()), Toast.LENGTH_SHORT)
                    .show();
        }

        super.onDestroy();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        // Remove pending messages related to update suggestions
        abortCorrection(true, false);
    }

    AnyKeyboardView getInputView() {
        return mInputView;
    }

    @Override
    public void setInputView(@NonNull View view) {
        super.setInputView(view);
        //setKeyboardFinalStuff(NextKeyboardType.Alphabet);
        ViewParent parent = view.getParent();
        if (parent instanceof View) {
            // this is required for animations, so the background will be
            // consist.
            ((View) parent).setBackgroundResource(R.drawable.ask_wallpaper);
        } else {
            Log.w(TAG, "*** It seams that the InputView parent is not a View!! This is very strange.");
        }
    }

    @Override
    public View onCreateInputView() {
        if (mInputView != null) mInputView.onViewNotRequired();
        mInputView = null;

        GCUtils.getInstance().performOperationWithMemRetry(TAG,
                new MemRelatedOperation() {
                    public void operation() {
                        mInputView = (AnyKeyboardView) getLayoutInflater().inflate(R.layout.main_keyboard_layout, null);
                    }
                }, true);
        // resetting token users
        mOptionsDialog = null;

        mKeyboardSwitcher.setInputView(mInputView);
        mInputView.setOnKeyboardActionListener(this);

        mDistinctMultiTouch = mInputView.hasDistinctMultitouch();

        return mInputView;
    }

    @Override
    public View onCreateCandidatesView() {
        return getLayoutInflater().inflate(R.layout.candidates, null);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        //removing close request (if it was asked for a previous onFinishInput).
        mKeyboardHandler.removeMessages(KeyboardUIStateHandler.MSG_CLOSE_DICTIONARIES);

        abortCorrection(true, false);

        if (!restarting) {
            TextEntryState.newSession(this);
            // Clear shift states.
            mMetaState = 0;
            mCurrentlyAllowSuggestionRestart = mAllowSuggestionsRestart;
        } else {
            // something very fishy happening here...
            // this is the only way I can get around it.
            // it seems that when a onStartInput is called with restarting ==
            // true
            // suggestions restart fails :(
            // see Browser when editing multiline textbox
            mCurrentlyAllowSuggestionRestart = false;
        }

        setKeyboardStatusIcon();
    }

    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        //in case the user has used physical keyboard with this input-field,
        //we will not show the keyboard view (until completely finishing, or switching input fields)
        final boolean previouslyPhysicalKeyboardInput;
        if ((!configChange) && editorInfo != null && editorInfo.fieldId == mLastEditorIdPhysicalKeyboardWasUsed && editorInfo.fieldId != 0) {
            Log.d(TAG, "Already used physical keyboard on this input-field. Will not show keyboard view.");
            previouslyPhysicalKeyboardInput = true;
        } else {
            previouslyPhysicalKeyboardInput = false;
            mLastEditorIdPhysicalKeyboardWasUsed = 0;
        }
        return (!previouslyPhysicalKeyboardInput) && super.onShowInputRequested(flags, configChange);
    }

    @Override
    public void onStartInputView(final EditorInfo attribute, final boolean restarting) {
        Log.v(TAG, "onStartInputView(EditorInfo{imeOptions %d, inputType %d}, restarting %s",
                attribute.imeOptions, attribute.inputType, restarting);

        super.onStartInputView(attribute, restarting);

        if (mVoiceRecognitionTrigger != null) {
            mVoiceRecognitionTrigger.onStartInputView();
        }

        if (mInputView == null) {
            return;
        }

        mInputView.dismissPopupKeyboard();
        mInputView.setKeyboardActionType(attribute.imeOptions);

        mPredictionOn = false;
        mCompletionOn = false;
        mCompletions = null;

        switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
            case EditorInfo.TYPE_CLASS_DATETIME:
                Log.d(TAG, "Setting MODE_DATETIME as keyboard due to a TYPE_CLASS_DATETIME input.");
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_DATETIME, attribute, restarting);
                break;
            case EditorInfo.TYPE_CLASS_NUMBER:
                Log.d(TAG, "Setting MODE_NUMBERS as keyboard due to a TYPE_CLASS_NUMBER input.");
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_NUMBERS, attribute, restarting);
                break;
            case EditorInfo.TYPE_CLASS_PHONE:
                Log.d(TAG, "Setting MODE_PHONE as keyboard due to a TYPE_CLASS_PHONE input.");
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_PHONE, attribute, restarting);
                break;
            case EditorInfo.TYPE_CLASS_TEXT:
                Log.d(TAG, "A TYPE_CLASS_TEXT input.");
                final int variation = attribute.inputType & EditorInfo.TYPE_MASK_VARIATION;
                switch (variation) {
                    case EditorInfo.TYPE_TEXT_VARIATION_PASSWORD:
                    case EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                    case EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD:
                        Log.d(TAG, "A password TYPE_CLASS_TEXT input with no prediction");
                        mPredictionOn = false;
                        break;
                    default:
                        mPredictionOn = true;
                }

                if (mAskPrefs.getInsertSpaceAfterCandidatePick()) {
                    switch (variation) {
                        case EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                        case EditorInfo.TYPE_TEXT_VARIATION_URI:
                        case EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                            mAutoSpace = false;
                            break;
                        default:
                            mAutoSpace = true;
                    }
                } else {
                    // some users don't want auto-space
                    mAutoSpace = false;
                }

                switch (variation) {
                    case EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                    case EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                        Log.d(TAG, "Setting MODE_EMAIL as keyboard due to a TYPE_TEXT_VARIATION_EMAIL_ADDRESS input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_EMAIL, attribute, restarting);
                        mPredictionOn = false;
                        break;
                    case EditorInfo.TYPE_TEXT_VARIATION_URI:
                        Log.d(TAG, "Setting MODE_URL as keyboard due to a TYPE_TEXT_VARIATION_URI input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_URL, attribute, restarting);
                        mPredictionOn = false;
                        break;
                    case EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE:
                        Log.d(TAG, "Setting MODE_IM as keyboard due to a TYPE_TEXT_VARIATION_SHORT_MESSAGE input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_IM, attribute, restarting);
                        break;
                    default:
                        Log.d(TAG, "Setting MODE_TEXT as keyboard due to a default input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, attribute, restarting);
                }

                final int textFlag = attribute.inputType & EditorInfo.TYPE_MASK_FLAGS;
                if ((textFlag & EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS ||
                        (textFlag & EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE) == EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE) {
                        Log.d(TAG, "Input requested NO_SUGGESTIONS, or it is AUTO_COMPLETE by itself.");
                        mPredictionOn = false;
                }

                break;
            default:
                Log.d(TAG, "Setting MODE_TEXT as keyboard due to a default input.");
                // No class. Probably a console window, or no GUI input connection
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, attribute, restarting);
                mPredictionOn = false;
                mAutoSpace = true;
        }

        mPredicting = false;
        mJustAddedAutoSpace = false;
        setCandidatesViewShown(false);

        mPredictionOn = mPredictionOn && (mShowSuggestions/* || mQuickFixes */);

        clearSuggestions();

        if (mPredictionOn) {
            if (mLastDictionaryRefresh < 0 || (SystemClock.elapsedRealtime() - mLastDictionaryRefresh) > MINIMUM_REFRESH_TIME_FOR_DICTIONARIES) {
                //refreshing dictionary
                setDictionariesForCurrentKeyboard();
            }
        }

        updateShiftStateNow();
    }

    @Override
    public void hideWindow() {
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }

        super.hideWindow();

        TextEntryState.endSession();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        //properly finished input. Next time we DO want to show the keyboard view
        mLastEditorIdPhysicalKeyboardWasUsed = 0;

        hideWindow();

        if (mShowKeyboardIconInStatusBar) {
            mInputMethodManager.hideStatusIcon(mImeToken);
        }
        mKeyboardHandler.sendEmptyMessageDelayed(KeyboardUIStateHandler.MSG_CLOSE_DICTIONARIES, CLOSE_DICTIONARIES_DELAY);
    }

    /*
     * this function is called EVERY TIME them selection is changed. This also
     * includes the underlined suggestions.
     */
    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

        if (BuildConfig.DEBUG) Log.d(TAG, "onUpdateSelection: oss=%d, ose=%d, nss=%d, nse=%d, cs=%d, ce=%d",
                oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

        mGlobalCursorPosition = newSelEnd;
        mGlobalSelectionStartPosition = newSelStart;
        if (mUndoCommitCursorPosition == UNDO_COMMIT_WAITING_TO_RECORD_POSITION) {
            Log.d(TAG, "onUpdateSelection: I am in ACCEPTED_DEFAULT state, time to store the position - I can only undo-commit from here.");
            mUndoCommitCursorPosition = newSelStart;
        }
        updateShiftStateNow();

        final boolean isExpectedEvent = SystemClock.uptimeMillis() < mExpectingSelectionUpdateBy;
        mExpectingSelectionUpdateBy = NEVER_TIME_STAMP;

        if (isExpectedEvent) {
            Log.v(TAG, "onUpdateSelection: Expected event. Discarding.");
            return;
        }

        if (!isPredictionOn()) {
            return;// not relevant if no prediction is needed.
        }

        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;// well, I can't do anything without this connection

        Log.d(TAG, "onUpdateSelection: ok, let's see what can be done");

        if (newSelStart != newSelEnd) {
            // text selection. can't predict in this mode
            Log.d(TAG, "onUpdateSelection: text selection.");
            abortCorrection(true, false);
        } else {
            // we have the following options (we are in an input which requires
            // predicting (mPredictionOn == true):
            // 1) predicting and moved inside the word
            // 2) predicting and moved outside the word
            // 2.1) to a new word
            // 2.2) to no word land
            // 3) not predicting
            // 3.1) to a new word
            // 3.2) to no word land

            // so, 1 and 2 requires that predicting is currently done, and the
            // cursor moved
            if (mPredicting) {
                if (newSelStart >= candidatesStart && newSelStart <= candidatesEnd) {
                    // 1) predicting and moved inside the word - just update the
                    // cursor position and shift state
                    // inside the currently selected word
                    int cursorPosition = newSelEnd - candidatesStart;
                    if (mWord.setCursorPosition(cursorPosition)) {
                        Log.d(TAG, "onUpdateSelection: cursor moving inside the predicting word");
                    }
                } else {
                    Log.d(TAG, "onUpdateSelection: cursor moving outside the currently predicting word");
                    abortCorrection(true, false);
                    // ask user whether to restart
                    postRestartWordSuggestion();
                }
            } else {
                Log.d(TAG, "onUpdateSelection: not predicting at this moment, maybe the cursor is now at a new word?");
                if (TextEntryState.willUndoCommitOnBackspace()){
                    if (mUndoCommitCursorPosition == oldSelStart && mUndoCommitCursorPosition != newSelStart) {
                        Log.d(TAG, "onUpdateSelection: I am in a state that is position sensitive but the user moved the cursor, so it is not possible to undo_commit now.");
                        abortCorrection(true, false);
                    }
                }
                postRestartWordSuggestion();
            }
        }
    }

    private void postRestartWordSuggestion() {
        mKeyboardHandler.removeMessages(KeyboardUIStateHandler.MSG_RESTART_NEW_WORD_SUGGESTIONS);
        mKeyboardHandler.sendEmptyMessageDelayed(KeyboardUIStateHandler.MSG_RESTART_NEW_WORD_SUGGESTIONS, 10 * ONE_FRAME_DELAY);
    }

    private boolean canRestartWordSuggestion() {
        if (mPredicting || !isPredictionOn() || !mAllowSuggestionsRestart
                || !mCurrentlyAllowSuggestionRestart || mInputView == null
                || !mInputView.isShown()) {
            // why?
            // mPredicting - if I'm predicting a word, I can not restart it..
            // right? I'm inside that word!
            // isPredictionOn() - this is obvious.
            // mAllowSuggestionsRestart - config settings
            // mCurrentlyAllowSuggestionRestart - workaround for
            // onInputStart(restarting == true)
            // mInputView == null - obvious, no?
            Log.d(TAG, "performRestartWordSuggestion: no need to restart: mPredicting=%s, isPredictionOn=%s, mAllowSuggestionsRestart=%s, mCurrentlyAllowSuggestionRestart=%s"
                    , mPredicting, isPredictionOn(), mAllowSuggestionsRestart, mCurrentlyAllowSuggestionRestart);
            return false;
        } else if (!isCursorTouchingWord()) {
            Log.d(TAG, "User moved cursor to no-man land. Bye bye.");
            return false;
        }

        return true;
    }

    public void performRestartWordSuggestion(final InputConnection ic) {
        // I assume ASK DOES NOT predict at this moment!

        // 2) predicting and moved outside the word - abort predicting, update
        // shift state
        // 2.1) to a new word - restart predicting on the new word
        // 2.2) to no word land - nothing else

        // this means that the new cursor position is outside the candidates
        // underline
        // this can be either because the cursor is really outside the
        // previously underlined (suggested)
        // or nothing was suggested.
        // in this case, we would like to reset the prediction and restart
        // if the user clicked inside a different word
        // restart required?
        if (canRestartWordSuggestion()) {// 2.1
            ic.beginBatchEdit();// don't want any events till I finish handling
            // this touch
            abortCorrection(true, false);

            // locating the word
            CharSequence toLeft = "";
            CharSequence toRight = "";
            while (true) {
                CharSequence newToLeft = ic.getTextBeforeCursor(toLeft.length() + 1, 0);
                if (TextUtils.isEmpty(newToLeft)
                        || isWordSeparator(newToLeft.charAt(0))
                        || newToLeft.length() == toLeft.length()) {
                    break;
                }
                toLeft = newToLeft;
            }
            while (true) {
                CharSequence newToRight = ic.getTextAfterCursor(toRight.length() + 1, 0);
                if (TextUtils.isEmpty(newToRight)
                        || isWordSeparator(newToRight.charAt(newToRight.length() - 1))
                        || newToRight.length() == toRight.length()) {
                    break;
                }
                toRight = newToRight;
            }
            CharSequence word = toLeft.toString() + toRight.toString();
            Log.d(TAG, "Starting new prediction on word '%s'.", word);
            mPredicting = word.length() > 0;
            mUndoCommitCursorPosition = UNDO_COMMIT_NONE;
            mWord.reset();

            final int[] tempNearByKeys = new int[1];

            for (int index = 0; index < word.length(); index++) {
                final char c = word.charAt(index);
                if (index == 0) mWord.setFirstCharCapitalized(Character.isUpperCase(c));

                tempNearByKeys[0] = c;
                mWord.add(c, tempNearByKeys);

                TextEntryState.typedCharacter(c, false);
            }
            ic.deleteSurroundingText(toLeft.length(), toRight.length());
            ic.setComposingText(word, 1);
            // repositioning the cursor
            if (toRight.length() > 0) {
                final int cursorPosition = getCursorPosition(ic) - toRight.length();
                Log.d(TAG, "Repositioning the cursor inside the word to position %d", cursorPosition);
                ic.setSelection(cursorPosition, cursorPosition);
            }

            mWord.setCursorPosition(toLeft.length());
            ic.endBatchEdit();
            postUpdateSuggestions();
        } else {
            Log.d(TAG, "performRestartWordSuggestion canRestartWordSuggestion == false");
        }
    }

    private void onPhysicalKeyboardKeyPressed() {
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        mLastEditorIdPhysicalKeyboardWasUsed = editorInfo == null? 0 : editorInfo.fieldId;
        if (mAskPrefs.hideSoftKeyboardWhenPhysicalKeyPressed()) {
            hideWindow();
        }

        // For all other keys, if we want to do transformations on
        // text being entered with a hard keyboard, we need to process
        // it and do the appropriate action.
        // using physical keyboard is more annoying with candidate view in
        // the way
        // so we disable it.

        // to clear the underline.
        abortCorrection(true, false);
    }

    @Override
    public void onComputeInsets(@NonNull InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] completions) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Received completions:");
            for (int i = 0; i < (completions != null ? completions.length : 0); i++) {
                Log.d(TAG, "  #" + i + ": " + completions[i]);
            }
        }

        // completions should be shown if dictionary requires, or if we are in
        // full-screen and have outside completions
        if (mCompletionOn || (isFullscreenMode() && (completions != null))) {
            mCompletions = completions;
            // we do completions :)

            mCompletionOn = true;
            if (completions == null) {
                clearSuggestions();
                return;
            }

            List<CharSequence> stringList = new ArrayList<>();
            for (CompletionInfo ci : completions) {
                if (ci != null) stringList.add(ci.getText());
            }
            // CharSequence typedWord = mWord.getTypedWord();
            setSuggestions(stringList, true, true, true);
            mWord.setPreferredWord(null);
            // I mean, if I'm here, it must be shown...
            setCandidatesViewShown(true);
        }
    }

    @Override
    public void setCandidatesViewShown(boolean shown) {
        // we show predication only in on-screen keyboard
        // (onEvaluateInputViewShown)
        // or if the physical keyboard supports candidates
        // (mPredictionLandscape)
        final boolean shouldShow = shouldCandidatesStripBeShown() && shown;
        final boolean currentlyShown = mCandidatesParent != null && mCandidatesParent.getVisibility() == View.VISIBLE;
        super.setCandidatesViewShown(shouldShow);
        if (shouldShow != currentlyShown) {
            // I believe (can't confirm it) that candidates animation is kinda rare,
            // and it is better to load it on demand, then to keep it in memory always..
            if (shouldShow) {
                mCandidatesParent.setAnimation(AnimationUtils.loadAnimation(this, R.anim.candidates_bottom_to_up_enter));
            } else {
                mCandidatesParent.setAnimation(AnimationUtils.loadAnimation(this, R.anim.candidates_up_to_bottom_exit));
            }
        }
    }

    @Override
    public void setCandidatesView(@NonNull View view) {
        super.setCandidatesView(view);
        mCandidatesParent = view.getParent() instanceof View ? (View) view.getParent() : null;

        mCandidateView = (CandidateView) view.findViewById(R.id.candidates);
        mCandidateView.setService(this);
        setCandidatesViewShown(false);

        final KeyboardTheme theme = KeyboardThemeFactory.getCurrentKeyboardTheme(getApplicationContext());
        final TypedArray a = theme.getPackageContext().obtainStyledAttributes(null, R.styleable.AnyKeyboardViewTheme, 0, theme.getThemeResId());
        int closeTextColor = ContextCompat.getColor(this, R.color.candidate_other);
        float fontSizePixel = getResources().getDimensionPixelSize(R.dimen.candidate_font_height);
        try {
            closeTextColor = a.getColor(R.styleable.AnyKeyboardViewTheme_suggestionOthersTextColor, closeTextColor);
            fontSizePixel = a.getDimension(R.styleable.AnyKeyboardViewTheme_suggestionTextSize, fontSizePixel);
        } catch (Exception e) {
            e.printStackTrace();
        }
        a.recycle();

        mCandidateCloseText = (TextView) view.findViewById(R.id.close_suggestions_strip_text);
        View closeIcon = view.findViewById(R.id.close_suggestions_strip_icon);

        closeIcon.setOnClickListener(new OnClickListener() {
            // two seconds is enough.
            private final static long DOUBLE_TAP_TIMEOUT = 2 * 1000 - 50;

            public void onClick(View v) {
                mKeyboardHandler.removeMessages(KeyboardUIStateHandler.MSG_REMOVE_CLOSE_SUGGESTIONS_HINT);
                mCandidateCloseText.setVisibility(View.VISIBLE);
                mCandidateCloseText.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(), R.anim.close_candidates_hint_in));
                mKeyboardHandler.sendMessageDelayed(mKeyboardHandler.obtainMessage(KeyboardUIStateHandler.MSG_REMOVE_CLOSE_SUGGESTIONS_HINT), DOUBLE_TAP_TIMEOUT);
            }
        });

        mCandidateCloseText.setTextColor(closeTextColor);
        mCandidateCloseText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePixel);
        mCandidateCloseText.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mKeyboardHandler.removeMessages(KeyboardUIStateHandler.MSG_REMOVE_CLOSE_SUGGESTIONS_HINT);
                mCandidateCloseText.setVisibility(View.GONE);
                abortCorrection(true, true);
            }
        });
    }

    private void clearSuggestions() {
        setSuggestions(null, false, false, false);
    }

    private void setSuggestions(List<CharSequence> suggestions,
                                boolean completions, boolean typedWordValid,
                                boolean haveMinimalSuggestion) {
        if (mCandidateView != null) {
            mCandidateView.setSuggestions(suggestions, completions,
                    typedWordValid, haveMinimalSuggestion && mAutoCorrectOn);
        }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        if (getCurrentInputEditorInfo() != null) {
            final EditorInfo editorInfo = getCurrentInputEditorInfo();
            if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0) {
                //if the view DOES NOT want fullscreen, then do what it wants
                Log.d(TAG, "Will not go to Fullscreen because input view requested IME_FLAG_NO_FULLSCREEN");
                return false;
            } else if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0) {
                Log.d(TAG, "Will not go to Fullscreen because input view requested IME_FLAG_NO_EXTRACT_UI");
                return false;

            }
        }

        switch (mOrientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                return mAskPrefs.getUseFullScreenInputInLandscape();
            default:
                return mAskPrefs.getUseFullScreenInputInPortrait();
        }
    }

    @Override
    public boolean onKeyDown(final int keyEventKeyCode, @NonNull KeyEvent event) {
        InputConnection ic = getCurrentInputConnection();
        if (handleSelectionExpending(keyEventKeyCode, ic)) return true;
        final boolean shouldTranslateSpecialKeys = isInputViewShown();

        //greater than zero means it is a physical keyboard.
        //we also want to hide the view if it's a glyph (for example, not physical volume-up key)
        if (event.getDeviceId() > 0 && event.isPrintingKey()) onPhysicalKeyboardKeyPressed();

        mHardKeyboardAction.initializeAction(event, mMetaState);

        switch (keyEventKeyCode) {
            /****
             * SPECIAL translated HW keys If you add new keys here, do not forget
             * to add to the
             */
            case KeyEvent.KEYCODE_CAMERA:
                if (shouldTranslateSpecialKeys
                        && mAskPrefs.useCameraKeyForBackspaceBackword()) {
                    handleBackWord(getCurrentInputConnection());
                    return true;
                }
                // DO NOT DELAY CAMERA KEY with unneeded checks in default mark
                return super.onKeyDown(keyEventKeyCode, event);
            case KeyEvent.KEYCODE_FOCUS:
                if (shouldTranslateSpecialKeys
                        && mAskPrefs.useCameraKeyForBackspaceBackword()) {
                    handleDeleteLastCharacter(false);
                    return true;
                }
                // DO NOT DELAY FOCUS KEY with unneeded checks in default mark
                return super.onKeyDown(keyEventKeyCode, event);
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (shouldTranslateSpecialKeys
                        && mAskPrefs.useVolumeKeyForLeftRight()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                    return true;
                }
                // DO NOT DELAY VOLUME UP KEY with unneeded checks in default
                // mark
                return super.onKeyDown(keyEventKeyCode, event);
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (shouldTranslateSpecialKeys
                        && mAskPrefs.useVolumeKeyForLeftRight()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                    return true;
                }
                // DO NOT DELAY VOLUME DOWN KEY with unneeded checks in default
                // mark
                return super.onKeyDown(keyEventKeyCode, event);
            /****
             * END of SPECIAL translated HW keys code section
             */
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0 && mInputView != null) {
                    if (mInputView.handleBack()) {
                        // consuming the meta keys
                        if (ic != null) {
                            // translated, so we also take care of the metakeys
                            ic.clearMetaKeyStates(Integer.MAX_VALUE);
                        }
                        mMetaState = 0;
                        return true;
                    }
                }
                break;
            case 0x000000cc:// API 14: KeyEvent.KEYCODE_LANGUAGE_SWITCH
                switchToNextPhysicalKeyboard(ic);
                return true;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                if (event.isAltPressed()
                        && Workarounds.isAltSpaceLangSwitchNotPossible()) {
                    switchToNextPhysicalKeyboard(ic);
                    return true;
                }
                // NOTE: letting it fall-through to the other meta-keys
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_SYM:
                Log.d(TAG + "-meta-key",
                        getMetaKeysStates("onKeyDown before handle"));
                mMetaState = MyMetaKeyKeyListener.handleKeyDown(mMetaState,
                        keyEventKeyCode, event);
                Log.d(TAG + "-meta-key",
                        getMetaKeysStates("onKeyDown after handle"));
                break;
            case KeyEvent.KEYCODE_SPACE:
                if ((event.isAltPressed() && !Workarounds
                        .isAltSpaceLangSwitchNotPossible())
                        || event.isShiftPressed()) {
                    switchToNextPhysicalKeyboard(ic);
                    return true;
                }
                // NOTE:
                // letting it fall through to the "default"
            default:

                // Fix issue 185, check if we should process key repeat
                if (!mAskPrefs.getUseRepeatingKeys() && event.getRepeatCount() > 0)
                    return true;

                if (mKeyboardSwitcher.isCurrentKeyboardPhysical()) {
                    // sometimes, the physical keyboard will delete input, and
                    // then
                    // add some.
                    // we'll try to make it nice
                    if (ic != null)
                        ic.beginBatchEdit();
                    try {
                        // issue 393, backword on the hw keyboard!
                        if (mAskPrefs.useBackword()
                                && keyEventKeyCode == KeyEvent.KEYCODE_DEL
                                && event.isShiftPressed()) {
                            handleBackWord(ic);
                            return true;
                        } else/* if (event.isPrintingKey()) */ {
                            // http://article.gmane.org/gmane.comp.handhelds.openmoko.android-freerunner/629
                            AnyKeyboard current = mKeyboardSwitcher
                                    .getCurrentKeyboard();

                            HardKeyboardTranslator keyTranslator = (HardKeyboardTranslator) current;

                            keyTranslator.translatePhysicalCharacter(mHardKeyboardAction, this);

                            if (mHardKeyboardAction.getKeyCodeWasChanged()) {
                                final int translatedChar = mHardKeyboardAction.getKeyCode();
                                // typing my own.
                                onKey(translatedChar, null, -1, new int[]{translatedChar}, true/*faking from UI*/);
                                // my handling we are at a regular key press, so we'll update
                                // our meta-state member
                                mMetaState = MyMetaKeyKeyListener.adjustMetaAfterKeypress(mMetaState);
                                Log.d(TAG + "-meta-key", getMetaKeysStates("onKeyDown after adjust - translated"));
                                return true;
                            }
                        }
                    } finally {
                        if (ic != null)
                            ic.endBatchEdit();
                    }
                }
                if (event.isPrintingKey()) {
                    // we are at a regular key press, so we'll update our
                    // meta-state
                    // member
                    mMetaState = MyMetaKeyKeyListener
                            .adjustMetaAfterKeypress(mMetaState);
                    Log.d(TAG + "-meta-key",
                            getMetaKeysStates("onKeyDown after adjust"));
                }
        }
        return super.onKeyDown(keyEventKeyCode, event);
    }

    private boolean handleSelectionExpending(int keyEventKeyCode, InputConnection ic) {
        if (mArrowSelectionState && ic != null) {
            switch (keyEventKeyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    ic.setSelection(Math.max(0, mGlobalSelectionStartPosition-1), mGlobalCursorPosition);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    ic.setSelection(mGlobalSelectionStartPosition, mGlobalCursorPosition+1);
                    return true;
                default:
                    mArrowSelectionState = false;
            }
        }
        return false;
    }

    private void switchToNextPhysicalKeyboard(InputConnection ic) {
        // consuming the meta keys
        if (ic != null) {
            ic.clearMetaKeyStates(Integer.MAX_VALUE);// translated, so
            // we also take
            // care of the
            // metakeys.
        }
        mMetaState = 0;
        // only physical keyboard
        nextKeyboard(getCurrentInputEditorInfo(),
                NextKeyboardType.AlphabetSupportsPhysical);
    }

    private void setKeyboardStatusIcon() {
        if (mShowKeyboardIconInStatusBar && mKeyboardSwitcher.isAlphabetMode()) {
            mInputMethodManager.showStatusIcon(mImeToken,
                    getCurrentKeyboard().getKeyboardContext().getPackageName(),
                    getCurrentKeyboard().getKeyboardIconResId());
        }
    }

    public AnyKeyboard getCurrentKeyboard() {
        return mKeyboardSwitcher.getCurrentKeyboard();
    }

    public KeyboardSwitcher getKeyboardSwitcher() {
        return mKeyboardSwitcher;
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        Log.d(TAG, "onKeyUp keycode=%d", keyCode);
        switch (keyCode) {
            // Issue 248
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (!isInputViewShown()) {
                    return super.onKeyUp(keyCode, event);
                }
                if (mAskPrefs.useVolumeKeyForLeftRight()) {
                    // no need of vol up/down sound
                    return true;
                }
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mInputView != null && mInputView.isShown()
                        && mInputView.isShifted()) {
                    event = new KeyEvent(event.getDownTime(), event.getEventTime(),
                            event.getAction(), event.getKeyCode(),
                            event.getRepeatCount(), event.getDeviceId(),
                            event.getScanCode(), KeyEvent.META_SHIFT_LEFT_ON
                            | KeyEvent.META_SHIFT_ON);
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null)
                        ic.sendKeyEvent(event);

                    return true;
                }
                break;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_SYM:
                mMetaState = MyMetaKeyKeyListener.handleKeyUp(mMetaState, keyCode, event);
                Log.d(TAG + "-meta-key", getMetaKeysStates("onKeyUp"));
                setInputConnectionMetaStateAsCurrentMetaKeyKeyListenerState();
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    private String getMetaKeysStates(String place) {
        final int shiftState = MyMetaKeyKeyListener.getMetaState(mMetaState, MyMetaKeyKeyListener.META_SHIFT_ON);
        final int altState = MyMetaKeyKeyListener.getMetaState(mMetaState, MyMetaKeyKeyListener.META_ALT_ON);
        final int symState = MyMetaKeyKeyListener.getMetaState(mMetaState, MyMetaKeyKeyListener.META_SYM_ON);

        return "Meta keys state at " + place + "- SHIFT:" + shiftState
                + ", ALT:" + altState + " SYM:" + symState + " bits:"
                + MyMetaKeyKeyListener.getMetaState(mMetaState) + " state:"
                + mMetaState;
    }

    private void setInputConnectionMetaStateAsCurrentMetaKeyKeyListenerState() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            int clearStatesFlags = 0;
            if (MyMetaKeyKeyListener.getMetaState(mMetaState,
                    MyMetaKeyKeyListener.META_ALT_ON) == 0)
                clearStatesFlags += KeyEvent.META_ALT_ON;
            if (MyMetaKeyKeyListener.getMetaState(mMetaState,
                    MyMetaKeyKeyListener.META_SHIFT_ON) == 0)
                clearStatesFlags += KeyEvent.META_SHIFT_ON;
            if (MyMetaKeyKeyListener.getMetaState(mMetaState,
                    MyMetaKeyKeyListener.META_SYM_ON) == 0)
                clearStatesFlags += KeyEvent.META_SYM_ON;
            ic.clearMetaKeyStates(clearStatesFlags);
        }
    }

    private boolean checkAddToDictionaryWithAutoDictionary(WordComposer suggestion, AutoDictionary.AdditionType type) {
        if (suggestion == null || suggestion.length() < 1)
            return false;
        // Only auto-add to dictionary if auto-correct is ON. Otherwise we'll be
        // adding words in situations where the user or application really
        // didn't
        // want corrections enabled or learned.
        if (!mQuickFixes && !mShowSuggestions)
            return false;

        if (mAutoDictionary != null) {
            String suggestionToCheck = suggestion.getTypedWord().toString();
            if (!mSuggest.isValidWord(suggestionToCheck)) {

                final boolean added = mAutoDictionary.addWord(suggestion, type, this);
                if (added && mCandidateView != null) {
                    mCandidateView.notifyAboutWordAdded(suggestion.getTypedWord());
                }
                return added;
            }
        }
        return false;
    }

    private void commitTyped(@Nullable InputConnection inputConnection) {
        if (mPredicting) {
            mPredicting = false;
            if (mWord.length() > 0) {
                if (inputConnection != null) {
                    inputConnection.commitText(mWord.getTypedWord(), 1);
                }
                mCommittedLength = mWord.length();
                mCommittedWord = mWord.getTypedWord();
                TextEntryState.acceptedTyped(mWord.getTypedWord());
                checkAddToDictionaryWithAutoDictionary(mWord, AutoDictionary.AdditionType.Typed);
            }
            if (mKeyboardHandler.hasMessages(KeyboardUIStateHandler.MSG_UPDATE_SUGGESTIONS)) {
                postUpdateSuggestions(-1);
            }
        }
    }

    private void swapPunctuationAndSpace(@NonNull InputConnection ic, final char punctuationCharacter) {
        CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);

        if (lastTwo != null && lastTwo.length() == 2
                && lastTwo.charAt(0) == KeyCodes.SPACE
                && lastTwo.charAt(1) == punctuationCharacter) {
            ic.deleteSurroundingText(2, 0);
            ic.commitText(punctuationCharacter + " ", 1);
            mJustAddedAutoSpace = true;
        }
    }

    private void removeTrailingSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;

        CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
        if (lastOne != null && lastOne.length() == 1
                && lastOne.charAt(0) == KeyCodes.SPACE) {
            ic.deleteSurroundingText(1, 0);
        }
    }

    public boolean addWordToDictionary(String word) {
        boolean added = mSuggest.addWordToUserDictionary(word);
        if (added && mCandidateView != null)
            mCandidateView.notifyAboutWordAdded(word);
        return added;
    }

    public void removeFromUserDictionary(String word) {
        mJustAutoAddedWord = false;
        mSuggest.removeWordFromUserDictionary(word);
        abortCorrection(true, false);
        if (mCandidateView != null)
            mCandidateView.notifyAboutRemovedWord(word);
    }

    /**
     * Helper to determine if a given character code is alphabetic.
     */
    private boolean isAlphabet(int code) {
        // inner letters have more options: ' in English. " in Hebrew, and more.
        if (mPredicting)
            return getCurrentKeyboard().isInnerWordLetter((char) code);
        else
            return getCurrentKeyboard().isStartOfWordLetter((char) code);
    }

    public void onMultiTapStarted() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null)
            ic.beginBatchEdit();
        handleDeleteLastCharacter(true);
        if (mInputView != null)
            mInputView.setShifted(mLastCharacterWasShifted);
    }

    public void onMultiTapEnded() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null)
            ic.endBatchEdit();
        updateShiftStateNow();
    }

    public void onFunctionKey(int primaryCode, Key key, int multiTapIndex, int[] nearByKeyCodes, boolean fromUI) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onFunctionKey %d", primaryCode);

        final InputConnection ic = getCurrentInputConnection();

        switch (primaryCode) {
            case KeyCodes.DELETE:
                if (ic == null)// if we don't want to do anything, lets check null first.
                    break;
                // we do backword if the shift is pressed while pressing
                // backspace (like in a PC)
                // but this is true ONLY if the device has multitouch, or the
                // user specifically asked for it
                if (mInputView != null
                        && mInputView.isShifted()
                        && !mInputView.getKeyboard().isShiftLocked()
                        && ((mDistinctMultiTouch && mShiftKeyState.isPressed()) || mAskPrefs.useBackword())) {
                    handleBackWord(ic);
                } else {
                    handleDeleteLastCharacter(false);
                }
                break;
            case KeyCodes.SHIFT:
                if (fromUI) {
                    handleShift();
                } else {
                    //not from UI (user not actually pressed that button)
                    onPress(primaryCode);
                    onRelease(primaryCode);
                }
                break;
            case KeyCodes.SHIFT_LOCK:
                mShiftKeyState.toggleLocked();
                handleShift();
                break;
            case KeyCodes.DELETE_WORD:
                if (ic == null)// if we don't want to do anything, lets check
                    // null first.
                    break;
                handleBackWord(ic);
                break;
            case KeyCodes.CLEAR_INPUT:
                if (ic != null) {
                    ic.beginBatchEdit();
                    commitTyped(ic);
                    ic.deleteSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE);
                    ic.endBatchEdit();
                }
                break;
            case KeyCodes.CTRL:
                if (fromUI) {
                    handleControl();
                } else {
                    //not from UI (user not actually pressed that button)
                    onPress(primaryCode);
                    onRelease(primaryCode);
                }
                break;
            case KeyCodes.CTRL_LOCK:
                mControlKeyState.toggleLocked();
                handleControl();
                break;
            case KeyCodes.ARROW_LEFT:
            case KeyCodes.ARROW_RIGHT:
                final int keyEventKeyCode = primaryCode == KeyCodes.ARROW_LEFT?
                        KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                if (!handleSelectionExpending(keyEventKeyCode, ic)) {
                    sendDownUpKeyEvents(keyEventKeyCode);
                }
                break;
            case KeyCodes.ARROW_UP:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP);
                break;
            case KeyCodes.ARROW_DOWN:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN);
                break;
            case KeyCodes.MOVE_HOME:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    sendDownUpKeyEvents(0x0000007a/*API 11:KeyEvent.KEYCODE_MOVE_HOME*/);
                } else {
                    if (ic != null) {
                        CharSequence textBefore = ic.getTextBeforeCursor(1024, 0);
                        if (!TextUtils.isEmpty(textBefore)) {
                            int newPosition = textBefore.length() - 1;
                            while (newPosition > 0) {
                                char chatAt = textBefore.charAt(newPosition - 1);
                                if (chatAt == '\n' || chatAt == '\r') {
                                    break;
                                }
                                newPosition--;
                            }
                            if (newPosition < 0)
                                newPosition = 0;
                            ic.setSelection(newPosition, newPosition);
                        }
                    }
                }
                break;
            case KeyCodes.MOVE_END:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    //API 11: KeyEvent.KEYCODE_MOVE_END
                    sendDownUpKeyEvents(0x0000007b);
                } else {
                    if (ic != null) {
                        CharSequence textAfter = ic.getTextAfterCursor(1024, 0);
                        if (!TextUtils.isEmpty(textAfter)) {
                            int newPosition = 1;
                            while (newPosition < textAfter.length()) {
                                char chatAt = textAfter.charAt(newPosition);
                                if (chatAt == '\n' || chatAt == '\r') {
                                    break;
                                }
                                newPosition++;
                            }
                            if (newPosition > textAfter.length())
                                newPosition = textAfter.length();
                            try {
                                CharSequence textBefore = ic.getTextBeforeCursor(Integer.MAX_VALUE, 0);
                                if (!TextUtils.isEmpty(textBefore)) {
                                    newPosition = newPosition + textBefore.length();
                                }
                                ic.setSelection(newPosition, newPosition);
                            } catch (Throwable e/*I'm using Integer.MAX_VALUE, it's scary.*/) {
                                Log.w(TAG, "Failed to getTextBeforeCursor.", e);
                            }
                        }
                    }
                }
                break;
            case KeyCodes.VOICE_INPUT:
                if (mVoiceRecognitionTrigger.isInstalled()) {
                    mVoiceRecognitionTrigger.startVoiceRecognition(getCurrentKeyboard().getDefaultDictionaryLocale());
                } else {
                    Intent voiceInputNotInstalledIntent = new Intent(getApplicationContext(), VoiceInputNotInstalledActivity.class);
                    voiceInputNotInstalledIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(voiceInputNotInstalledIntent);
                }
                break;
            case KeyCodes.CANCEL:
                if (mOptionsDialog == null || !mOptionsDialog.isShowing()) {
                    handleClose();
                }
                break;
            case KeyCodes.SETTINGS:
                showOptionsMenu();
                break;
            case KeyCodes.SPLIT_LAYOUT:
            case KeyCodes.MERGE_LAYOUT:
            case KeyCodes.COMPACT_LAYOUT_TO_RIGHT:
            case KeyCodes.COMPACT_LAYOUT_TO_LEFT:
                if (getCurrentKeyboard() != null && mInputView != null) {
                    mKeyboardInCondensedMode = CondenseType.fromKeyCode(primaryCode);
                    setKeyboardForView(getCurrentKeyboard());
                }
                break;
            case KeyCodes.DOMAIN:
                onText(key, mAskPrefs.getDomainText());
                break;
            case KeyCodes.QUICK_TEXT:
                if (mDoNotFlipQuickTextKeyAndPopupFunctionality) {
                    outputCurrentQuickTextKey(key);
                } else {
                    openQuickTextPopup(key);
                }
                break;
            case KeyCodes.QUICK_TEXT_POPUP:
                if (mDoNotFlipQuickTextKeyAndPopupFunctionality) {
                    openQuickTextPopup(key);
                } else {
                    outputCurrentQuickTextKey(key);
                }
                break;
            case KeyCodes.MODE_SYMOBLS:
                nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.Symbols);
                break;
            case KeyCodes.MODE_ALPHABET:
                if (mKeyboardSwitcher.shouldPopupForLanguageSwitch()) {
                    showLanguageSelectionDialog();
                } else
                    nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.Alphabet);
                break;
            case KeyCodes.UTILITY_KEYBOARD:
                mInputView.openUtilityKeyboard();
                break;
            case KeyCodes.MODE_ALPHABET_POPUP:
                showLanguageSelectionDialog();
                break;
            case KeyCodes.ALT:
                nextAlterKeyboard(getCurrentInputEditorInfo());
                break;
            case KeyCodes.KEYBOARD_CYCLE:
                nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.Any);
                break;
            case KeyCodes.KEYBOARD_REVERSE_CYCLE:
                nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.PreviousAny);
                break;
            case KeyCodes.KEYBOARD_CYCLE_INSIDE_MODE:
                nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.AnyInsideMode);
                break;
            case KeyCodes.KEYBOARD_MODE_CHANGE:
                nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.OtherMode);
                break;
            case KeyCodes.CLIPBOARD_COPY:
            case KeyCodes.CLIPBOARD_PASTE:
            case KeyCodes.CLIPBOARD_CUT:
                handleClipboardOperation(key, primaryCode);
                break;
            case KeyCodes.CLIPBOARD_SELECT_ALL:
                final CharSequence toLeft = ic.getTextBeforeCursor(10240, 0);
                final CharSequence toRight = ic.getTextAfterCursor(10240, 0);
                final int leftLength = toLeft == null? 0 : toLeft.length();
                final int rightLength = toRight == null? 0 : toRight.length();
                if (leftLength != 0 || rightLength != 0) {
                    ic.setSelection(0, leftLength + rightLength);
                }
                break;
            case KeyCodes.CLIPBOARD_SELECT:
                mArrowSelectionState = !mArrowSelectionState;
                break;
            case KeyCodes.CLIPBOARD_PASTE_POPUP:
                showAllClipboardEntries(key);
                break;
            default:
                if (BuildConfig.DEBUG) {
                    //this should not happen! We should handle ALL function keys.
                    throw new RuntimeException("UNHANDLED FUNCTION KEY! primary code "+primaryCode);
                } else {
                    Log.w(TAG, "UNHANDLED FUNCTION KEY! primary code %d. Ignoring.", primaryCode);
                }
        }
    }

    public void onNonFunctionKey(int primaryCode, Key key, int multiTapIndex, int[] nearByKeyCodes, boolean fromUI) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onFunctionKey %d", primaryCode);

        final InputConnection ic = getCurrentInputConnection();

        switch (primaryCode) {
            case KeyCodes.ENTER:
            case KeyCodes.SPACE:
                //shortcut. Nothing more.
                handleSeparator(primaryCode);
                //should we switch to alphabet keyboard?
                if (!mKeyboardSwitcher.isAlphabetMode()) {
                    Log.d(TAG, "SPACE/ENTER while in symbols mode");
                    if (mAskPrefs.getSwitchKeyboardOnSpace()) {
                        Log.d(TAG, "Switching to Alphabet is required by the user");
                        mKeyboardSwitcher.nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.Alphabet);
                    }
                }
                break;
            case KeyCodes.TAB:
                sendTab();
                break;
            case KeyCodes.ESCAPE:
                sendEscape();
                break;
            default:
                // Issue 146: Right to left languages require reversed parenthesis
                if (mKeyboardSwitcher.isRightToLeftMode()) {
                    if (primaryCode == (int) ')')
                        primaryCode = (int) '(';
                    else if (primaryCode == (int) '(')
                        primaryCode = (int) ')';
                }

                if (isWordSeparator(primaryCode)) {
                    handleSeparator(primaryCode);
                } else {
                    if (mControlKeyState.isActive() && primaryCode >= 32 && primaryCode < 127) {
                        // http://en.wikipedia.org/wiki/Control_character#How_control_characters_map_to_keyboards
                        int controlCode = primaryCode & 31;
                        Log.d(TAG, "CONTROL state: Char was %d and now it is %d", primaryCode, controlCode);
                        if (controlCode == 9) {
                            sendTab();
                        } else {
                            ic.commitText(Character.toString((char) controlCode), 1);
                        }
                    } else {
                        handleCharacter(primaryCode, key, multiTapIndex,
                                nearByKeyCodes);
                    }
                    mJustAddedAutoSpace = false;
                }
                break;
        }
    }

    public void onKey(int primaryCode, Key key, int multiTapIndex, int[] nearByKeyCodes, boolean fromUI) {
        if (primaryCode > 0) onNonFunctionKey(primaryCode, key, multiTapIndex, nearByKeyCodes, fromUI);
        else onFunctionKey(primaryCode, key, multiTapIndex, nearByKeyCodes, fromUI);

        setSpaceTimeStamp(primaryCode == KeyCodes.SPACE);
    }

    private void setSpaceTimeStamp(boolean isSpace) {
        if (isSpace) {
            mLastSpaceTimeStamp = SystemClock.uptimeMillis();
        } else {
            mLastSpaceTimeStamp = NEVER_TIME_STAMP;
        }
    }

    private void showAllClipboardEntries(final Key key) {
        Clipboard clipboard = AnyApplication.getFrankenRobot().embody(new Clipboard.ClipboardDiagram(getApplicationContext()));
        if (clipboard.getClipboardEntriesCount() == 0) {
            showToastMessage(R.string.clipboard_is_empty_toast, true);
        } else {
            final CharSequence[] entries = new CharSequence[clipboard.getClipboardEntriesCount()];
            for (int entryIndex=0; entryIndex<entries.length; entryIndex++) {
                entries[entryIndex] = clipboard.getText(entryIndex);
            }
            showOptionsDialogWithData(getText(R.string.clipboard_paste_entries_title), R.drawable.ic_clipboard_paste_light,
                    entries, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            onText(key, entries[which]);
                        }
                    });
        }
    }

    private void handleClipboardOperation(final Key key, final int primaryCode) {
        Clipboard clipboard = AnyApplication.getFrankenRobot().embody(new Clipboard.ClipboardDiagram(getApplicationContext()));
        switch (primaryCode) {
            case KeyCodes.CLIPBOARD_PASTE:
                CharSequence clipboardText = clipboard.getText(0/*last entry paste*/);
                if (!TextUtils.isEmpty(clipboardText)) {
                    onText(key, clipboardText);
                } else {
                    showToastMessage(R.string.clipboard_is_empty_toast, true);
                }
                break;
            case KeyCodes.CLIPBOARD_CUT:
            case KeyCodes.CLIPBOARD_COPY:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        CharSequence selectedText = ic.getSelectedText(InputConnection.GET_TEXT_WITH_STYLES);
                        if (!TextUtils.isEmpty(selectedText)) {
                            clipboard.setText(selectedText);
                            if (primaryCode == KeyCodes.CLIPBOARD_CUT) {
                                //sending a DEL key will delete the selected text
                                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                            }
                        }
                    }
                }
                break;
        }
    }

    private void openQuickTextPopup(Key key) {
        if (mInputView != null) {
            mInputView.showQuickKeysView(key);
        }
    }

    private void outputCurrentQuickTextKey(Key key) {
        QuickTextKey quickTextKey = QuickTextKeyFactory.getCurrentQuickTextKey(this);
        if (TextUtils.isEmpty(mOverrideQuickTextText))
            onText(key, quickTextKey.getKeyOutputText());
        else
            onText(key, mOverrideQuickTextText);
    }

    private boolean isTerminalEmulation() {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei == null) return false;

        switch(ei.packageName) {
            case "org.connectbot":
            case "org.woltage.irssiconnectbot":
            case "com.pslib.connectbot":
            case "com.sonelli.juicessh":
                return ei.inputType == 0;
            default:
                return false;
        }
    }

    private void sendTab() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        boolean tabHack = isTerminalEmulation();

        // Note: tab and ^I don't work in ConnectBot, hackish workaround
        if (tabHack) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_CENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_I));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I));
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_TAB));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_TAB));
        }
    }

    private void sendEscape() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        if (isTerminalEmulation()) {
            sendKeyChar((char) 27);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, 111 /* KEYCODE_ESCAPE */));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, 111 /* KEYCODE_ESCAPE */));
        }
    }

    public void setKeyboardForView(AnyKeyboard currentKeyboard) {
        currentKeyboard.setCondensedKeys(mKeyboardInCondensedMode);
        if (mInputView != null) {
            mInputView.setKeyboard(currentKeyboard);
        }
    }

    private void showOptionsDialogWithData(CharSequence title, @DrawableRes int iconRedId,
                                    final CharSequence[] entries, final DialogInterface.OnClickListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(iconRedId);
        builder.setTitle(title);
        builder.setNegativeButton(android.R.string.cancel, null);

        builder.setItems(entries, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface di, int position) {
                di.dismiss();
                if (di == mOptionsDialog) mOptionsDialog = null;

                if ((position < 0) || (position >= entries.length)) {
                    Log.d(TAG, "Selection dialog popup canceled");
                } else {
                    Log.d(TAG, "User selected '%s' at position %d", entries[position], position);
                    listener.onClick(di, position);
                }
            }
        });

        if (mOptionsDialog != null && mOptionsDialog.isShowing()) mOptionsDialog.dismiss();
        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mInputView.getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    private void showLanguageSelectionDialog() {
        KeyboardAddOnAndBuilder[] builders = mKeyboardSwitcher.getEnabledKeyboardsBuilders();
        ArrayList<CharSequence> keyboardsIds = new ArrayList<>();
        ArrayList<CharSequence> keyboards = new ArrayList<>();
        // going over all enabled keyboards
        for (KeyboardAddOnAndBuilder keyboardBuilder : builders) {
            keyboardsIds.add(keyboardBuilder.getId());
            String name = keyboardBuilder.getName();

            keyboards.add(name);
        }

        final CharSequence[] ids = new CharSequence[keyboardsIds.size()];
        final CharSequence[] items = new CharSequence[keyboards.size()];
        keyboardsIds.toArray(ids);
        keyboards.toArray(items);

        showOptionsDialogWithData(getText(R.string.select_keyboard_popup_title), R.drawable.ic_keyboard_globe_light,
                items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int position) {
                        CharSequence id = ids[position];
                        Log.d(TAG, "User selected '%s' with id %s", items[position], id);
                        EditorInfo currentEditorInfo = getCurrentInputEditorInfo();
                        mKeyboardSwitcher.nextAlphabetKeyboard(currentEditorInfo, id.toString());
                        setKeyboardFinalStuff();
                    }
                });
    }

    public void onText(Key key, CharSequence text) {
        Log.d(TAG, "onText: '%s'", text);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        ic.beginBatchEdit();
        if (mPredicting) {
            commitTyped(ic);
        }
        abortCorrection(true, false);
        ic.commitText(text, 1);
        ic.endBatchEdit();

        mJustAddedAutoSpace = false;
        mJustAddOnText = text;
        mCommittedWord = text;

        setSuggestions(mSuggest.getNextSuggestions(mCommittedWord, false), false, false, false);
    }

    private boolean performOnTextDeletion(InputConnection ic) {
        if (mJustAddOnText != null && ic != null) {
            final CharSequence onTextText = mJustAddOnText;
            mJustAddOnText = null;
            //just now, the user had cause onText to add text to input.
            //but after that, immediately pressed delete. So I'm guessing deleting the entire text is needed
            final int onTextLength = onTextText.length();
            Log.d(TAG, "Deleting the entire 'onText' input.");
            CharSequence cs = ic.getTextBeforeCursor(onTextLength, 0);
            if (onTextText.equals(cs)) {
                ic.deleteSurroundingText(onTextLength, 0);
                return true;
            }
        }

        return false;
    }

    private void handleBackWord(InputConnection ic) {
        if (ic == null) {
            return;
        }

        if (performOnTextDeletion(ic))
            return;

        if (mPredicting) {
            mWord.reset();
            mSuggest.resetNextWordSentence();
            mPredicting = false;
            ic.setComposingText("", 1);
            postUpdateSuggestions();
            return;
        }
        // I will not delete more than 128 characters. Just a safe-guard.
        // this will also allow me do just one call to getTextBeforeCursor!
        // Which is always good. This is a part of issue 951.
        CharSequence cs = ic.getTextBeforeCursor(128, 0);
        if (TextUtils.isEmpty(cs)) {
            return;// nothing to delete
        }
        // TWO OPTIONS
        // 1) Either we do like Linux and Windows (and probably ALL desktop
        // OSes):
        // Delete all the characters till a complete word was deleted:
        /*
         * What to do: We delete until we find a separator (the function
         * isBackWordStopChar). Note that we MUST delete a delete a whole word!
         * So if the back-word starts at separators, we'll delete those, and then
         * the word before: "test this,       ," -> "test "
         */
        // Pro: same as desktop
        // Con: when auto-caps is on (the default), this will delete the
        // previous word, which can be annoying..
        // E.g., Writing a sentence, then a period, then ASK will auto-caps,
        // then when the user press backspace (for some reason),
        // the entire previous word deletes.

        // 2) Or we delete all the characters till we encounter a separator, but
        // delete at least one character.
        /*
         * What to do: We delete until we find a separator (the function
         * isBackWordStopChar). Note that we MUST delete a delete at least one
         * character "test this, " -> "test this," -> "test this" -> "test "
         */
        // Pro: Supports auto-caps, and mostly similar to desktop OSes
        // Con: Not all desktop use-cases are here.

        // For now, I go with option 2, but I'm open for discussion.

        // 2b) "test this, " -> "test this"

        final int inputLength = cs.length();
        int idx = inputLength - 1;// it's OK since we checked whether cs is
        // empty after retrieving it.
        while (idx > 0 && !isBackWordStopChar((int) cs.charAt(idx))) {
            idx--;
        }
        ic.deleteSurroundingText(inputLength - idx, 0);// it is always > 0 !
    }

    private void handleDeleteLastCharacter(boolean forMultiTap) {
        InputConnection ic = getCurrentInputConnection();

        if (!forMultiTap && performOnTextDeletion(ic))
            return;

        boolean deleteChar = false;
        if (mPredicting) {
            final boolean wordManipulation = mWord.length() > 0 && mWord.cursorPosition() > 0;
            if (wordManipulation) {
                mWord.deleteLast();
                final int cursorPosition;
                if (mWord.cursorPosition() != mWord.length())
                    cursorPosition = getCursorPosition(ic);
                else
                    cursorPosition = -1;

                if (cursorPosition >= 0)
                    ic.beginBatchEdit();

                ic.setComposingText(mWord.getTypedWord(), 1);
                if (mWord.length() == 0) {
                    mPredicting = false;
                } else if (cursorPosition >= 0) {
                    ic.setSelection(cursorPosition - 1, cursorPosition - 1);
                }

                if (cursorPosition >= 0)
                    ic.endBatchEdit();

                postUpdateSuggestions();
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        } else {
            deleteChar = true;
        }

        TextEntryState.backspace();
        if (TextEntryState.getState() == TextEntryState.State.UNDO_COMMIT) {
            revertLastWord(deleteChar);
        } else if (deleteChar) {
            if (mCandidateView != null && mCandidateView.dismissAddToDictionaryHint()) {
                // Go back to the suggestion mode if the user canceled the
                // "Touch again to save".
                // NOTE: we don't revert the word when backspacing
                // from a manual suggestion pick. We deliberately chose a
                // different behavior only in the case of picking the first
                // suggestion (typed word). It's intentional to have made this
                // inconsistent with backspacing after selecting other
                // suggestions.
                revertLastWord(true/*this is a Delete character*/);
            } else {
                if (!forMultiTap) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                } else {
                    // this code tries to delete the text in a different way,
                    // because of multi-tap stuff
                    // using "deleteSurroundingText" will actually get the input
                    // updated faster!
                    // but will not handle "delete all selected text" feature,
                    // hence the "if (!forMultiTap)" above
                    final CharSequence beforeText = ic == null ? null : ic.getTextBeforeCursor(1, 0);
                    final int textLengthBeforeDelete = (TextUtils.isEmpty(beforeText)) ? 0 : beforeText.length();
                    if (textLengthBeforeDelete > 0)
                        ic.deleteSurroundingText(1, 0);
                    else
                        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                }
            }
        }
    }

    private void handleControl() {
        if (mInputView != null && mKeyboardSwitcher.isAlphabetMode()) {
            mInputView.setControl(mControlKeyState.isActive());
        }
    }

    private void handleShift() {
        if (mInputView != null) {
            Log.d(TAG, "shift Setting UI active:%s, locked: %s", mShiftKeyState.isActive(), mShiftKeyState.isLocked());
            mInputView.setShifted(mShiftKeyState.isActive());
            mInputView.setShiftLocked(mShiftKeyState.isLocked());
        }
    }

    private void abortCorrection(boolean force, boolean forever) {
        mSuggest.resetNextWordSentence();
        mJustAutoAddedWord = false;
        if (force || TextEntryState.isCorrecting()) {
            mKeyboardHandler.removeMessages(KeyboardUIStateHandler.MSG_UPDATE_SUGGESTIONS);
            mKeyboardHandler.removeMessages(KeyboardUIStateHandler.MSG_RESTART_NEW_WORD_SUGGESTIONS);

            final InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();

            clearSuggestions();

            TextEntryState.reset();
            mUndoCommitCursorPosition = UNDO_COMMIT_NONE;
            mWord.reset();
            mPredicting = false;
            mJustAddedAutoSpace = false;
            mJustAutoAddedWord = false;
            if (forever) {
                Log.d(TAG, "abortCorrection will abort correct forever");
                mPredictionOn = false;
                setCandidatesViewShown(false);
            }
        }
    }

    private void handleCharacter(final int primaryCode, Key key, int multiTapIndex, int[] nearByKeyCodes) {
        if (BuildConfig.DEBUG) Log.d(TAG, "handleCharacter: %d, isPredictionOn: %s, mPredicting: %s", primaryCode, isPredictionOn(), mPredicting);

        mExpectingSelectionUpdateBy = SystemClock.uptimeMillis() + MAX_TIME_TO_EXPECT_SELECTION_UPDATE;
        if (!mPredicting && isPredictionOn() && isAlphabet(primaryCode) && !isCursorTouchingWord()) {
            mPredicting = true;
            mUndoCommitCursorPosition = UNDO_COMMIT_NONE;
            mWord.reset();
            mAutoCorrectOn = mAutoComplete;
        }

        mLastCharacterWasShifted = (mInputView != null) && mInputView.isShifted();

        final int primaryCodeToOutput;
        if (mShiftKeyState.isActive()) {
            if (key != null) {
                primaryCodeToOutput = key.getCodeAtIndex(multiTapIndex, true);
            } else {
                primaryCodeToOutput = Character.toUpperCase(primaryCode);
            }
        } else {
            primaryCodeToOutput = primaryCode;
        }

        if (mPredicting) {
            if (mShiftKeyState.isActive() && mWord.cursorPosition() == 0) {
                mWord.setFirstCharCapitalized(true);
            }

            final InputConnection ic = getCurrentInputConnection();
            mWord.add(primaryCodeToOutput, nearByKeyCodes);
            ChewbaccaOnTheDrums.onKeyTyped(mWord, getApplicationContext());

            if (ic != null) {
                final int cursorPosition;
                if (mWord.cursorPosition() != mWord.length()) {
                    //Cursor is not at the end of the word. I'll need to reposition
                    cursorPosition = mGlobalCursorPosition + 1/*adding the new character*/;
                    ic.beginBatchEdit();
                } else {
                    cursorPosition = -1;
                }

                ic.setComposingText(mWord.getTypedWord(), 1);
                if (cursorPosition > 0) {
                    ic.setSelection(cursorPosition, cursorPosition);
                    ic.endBatchEdit();
                }
            }
            // this should be done ONLY if the key is a letter, and not a inner
            // character (like ').
            if (Character.isLetter((char) primaryCodeToOutput)) {
                postUpdateSuggestions();
            } else {
                // just replace the typed word in the candidates view
                if (mCandidateView != null)
                    mCandidateView.replaceTypedWord(mWord.getTypedWord());
            }
        } else {
            sendKeyChar((char) primaryCodeToOutput);
        }
        TextEntryState.typedCharacter((char) primaryCodeToOutput, false);
        mJustAutoAddedWord = false;
    }

    private void handleSeparator(int primaryCode) {
        mExpectingSelectionUpdateBy = SystemClock.uptimeMillis() + MAX_TIME_TO_EXPECT_SELECTION_UPDATE;
        //will not show next-word suggestion in case of a new line or if the separator is a sentence separator.
        boolean isEndOfSentence = (primaryCode == KeyCodes.ENTER || mSentenceSeparators.get(primaryCode));

        // Should dismiss the "Touch again to save" message when handling
        // separator
        if (mCandidateView != null && mCandidateView.dismissAddToDictionaryHint()) {
            postUpdateSuggestions();
        }

        // Handle separator
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        // this is a special case, when the user presses a separator WHILE
        // inside the predicted word.
        // in this case, I will want to just dump the separator.
        final boolean separatorInsideWord = (mWord.cursorPosition() < mWord.length());
        if (mPredicting && !separatorInsideWord) {
            // In certain languages where single quote is a separator, it's
            // better
            // not to auto correct, but accept the typed word. For instance,
            // in Italian dov' should not be expanded to dove' because the
            // elision
            // requires the last vowel to be removed.
            //Also, ACTION does not invoke default picking. See https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/198
            if (primaryCode != '\'') {
                pickDefaultSuggestion(mAutoCorrectOn && primaryCode != KeyCodes.ENTER);
                // Picked the suggestion by the space key. We consider this
                // as "added an auto space".
                if (primaryCode == KeyCodes.SPACE) {
                    mJustAddedAutoSpace = true;
                }
            } else {
                commitTyped(ic);
                if (isEndOfSentence) abortCorrection(true, false);
            }
        } else if (separatorInsideWord) {
            // when putting a separator in the middle of a word, there is no
            // need to do correction, or keep knowledge
            abortCorrection(true, false);
        }

        if (mJustAddedAutoSpace && primaryCode == KeyCodes.ENTER) {
            removeTrailingSpace();
            mJustAddedAutoSpace = false;
        }

        final EditorInfo ei = getCurrentInputEditorInfo();
        if (primaryCode == KeyCodes.ENTER && mShiftKeyState.isActive() && ic != null && ei != null && (ei.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
            //power-users feature ahead: Shift+Enter
            //getting away from firing the default editor action, by forcing newline
            ic.commitText("\n", 1);
        } else {
            sendKeyChar((char) primaryCode);
            TextEntryState.typedCharacter((char) primaryCode, true);

            if (ic != null) {
                if (primaryCode == KeyCodes.SPACE) {
                    if (mAskPrefs.isDoubleSpaceChangesToPeriod()) {
                        if ((SystemClock.uptimeMillis() - mLastSpaceTimeStamp) < ((long) mAskPrefs.getMultiTapTimeout())) {
                            ic.deleteSurroundingText(2, 0);
                            ic.commitText(". ", 1);
                            mJustAddedAutoSpace = true;
                            isEndOfSentence = true;
                        }
                    }
                } else if (mJustAddedAutoSpace &&
                        mAskPrefs.shouldSwapPunctuationAndSpace() &&
                        primaryCode != KeyCodes.ENTER &&
                        isSentenceSeparator(primaryCode)) {
                    swapPunctuationAndSpace(ic, (char)primaryCode);
                }
            }
        }

        if (ic != null) {
            ic.endBatchEdit();
        }

        if (isEndOfSentence) {
            mSuggest.resetNextWordSentence();
            clearSuggestions();
        } else if (!TextUtils.isEmpty(mCommittedWord)) {
            setSuggestions(mSuggest.getNextSuggestions(mCommittedWord, mWord.isAllUpperCase()), false, false, false);
            mWord.setFirstCharCapitalized(false);
        }
    }

    protected void handleClose() {
        boolean closeSelf = true;

        if (mInputView != null)
            closeSelf = mInputView.closing();

        if (closeSelf) {
            commitTyped(getCurrentInputConnection());
            requestHideSelf(0);
            abortCorrection(true, true);
            TextEntryState.endSession();
        }
    }

    private void postUpdateSuggestions() {
        postUpdateSuggestions(5 * ONE_FRAME_DELAY);
    }

    /**
     * posts an update suggestions request to the messages queue. Removes any previous request.
     *
     * @param delay negative value will cause the call to be done now, in this thread.
     */
    private void postUpdateSuggestions(long delay) {
        mKeyboardHandler.removeMessages(KeyboardUIStateHandler.MSG_UPDATE_SUGGESTIONS);
        if (delay > 0)
            mKeyboardHandler.sendMessageDelayed(mKeyboardHandler.obtainMessage(KeyboardUIStateHandler.MSG_UPDATE_SUGGESTIONS), delay);
        else if (delay == 0)
            mKeyboardHandler.sendMessage(mKeyboardHandler.obtainMessage(KeyboardUIStateHandler.MSG_UPDATE_SUGGESTIONS));
        else
            performUpdateSuggestions();
    }

    protected boolean isPredictionOn() {
        return mPredictionOn;
    }

    private boolean shouldCandidatesStripBeShown() {
        return mShowSuggestions && onEvaluateInputViewShown();
    }

    /*package*/ void performUpdateSuggestions() {
        if (mCandidateCloseText != null)// in API3 this variable is null
            mCandidateCloseText.setVisibility(View.GONE);

        if (!mPredicting) {
            clearSuggestions();
            return;
        }

        List<CharSequence> stringList = mSuggest.getSuggestions(/* mInputView, */mWord, false);
        boolean correctionAvailable = mSuggest.hasMinimalCorrection();
        // || mCorrectionMode == mSuggest.CORRECTION_FULL;
        CharSequence typedWord = mWord.getTypedWord();
        // If we're in basic correct
        boolean typedWordValid = mSuggest.isValidWord(typedWord);

        if (mShowSuggestions || mQuickFixes) {
            correctionAvailable |= typedWordValid;
        }

        // Don't auto-correct words with multiple capital letter
        correctionAvailable &= !mWord.isMostlyCaps();
        correctionAvailable &= !TextEntryState.isCorrecting();

        setSuggestions(stringList, false, typedWordValid, correctionAvailable);
        if (stringList.size() > 0) {
            if (correctionAvailable && !typedWordValid && stringList.size() > 1) {
                mWord.setPreferredWord(stringList.get(1));
            } else {
                mWord.setPreferredWord(typedWord);
            }
        } else {
            mWord.setPreferredWord(null);
        }
        setCandidatesViewShown(shouldCandidatesStripBeShown() || mCompletionOn);
    }

    private boolean pickDefaultSuggestion(boolean autoCorrectToPreferred) {

        // Complete any pending candidate query first
        if (mKeyboardHandler.hasMessages(KeyboardUIStateHandler.MSG_UPDATE_SUGGESTIONS)) {
            postUpdateSuggestions(-1);
        }

        final CharSequence typedWord = mWord.getTypedWord();
        final CharSequence bestWord = autoCorrectToPreferred? mWord.getPreferredWord() : typedWord;
        Log.d(TAG, "pickDefaultSuggestion: bestWord: %s, since mAutoCorrectOn is %s", bestWord, mAutoCorrectOn);

        if (!TextUtils.isEmpty(bestWord)) {
            TextEntryState.acceptedDefault(typedWord, bestWord);
            final boolean fixed = !typedWord.equals(pickSuggestion(bestWord, !bestWord.equals(typedWord)));
            if (!fixed) {//if the word typed was auto-replaced, we should not learn it.
                // Add the word to the auto dictionary if it's not a known word
                // this is "typed" if the auto-correction is off, or "picked" if it is on or momentarily off.
                checkAddToDictionaryWithAutoDictionary(mWord, mAutoComplete? AutoDictionary.AdditionType.Picked : AutoDictionary.AdditionType.Typed);
            }
            return true;
        }
        return false;
    }

    public void pickSuggestionManually(int index, CharSequence suggestion) {
        final boolean correcting = TextEntryState.isCorrecting();
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        try {
            if (mCompletionOn && mCompletions != null && index >= 0 && index < mCompletions.length) {
                CompletionInfo ci = mCompletions[index];
                if (ic != null) {
                    ic.commitCompletion(ci);
                }
                mCommittedLength = suggestion.length();
                mCommittedWord = suggestion;
                if (mCandidateView != null) {
                    mCandidateView.clear();
                }
                return;
            }
            pickSuggestion(suggestion, correcting);

            TextEntryState.acceptedSuggestion(mWord.getTypedWord(), suggestion);
            // Follow it with a space
            if (mAutoSpace && !correcting) {
                sendKeyChar((char) KeyCodes.SPACE);
                mJustAddedAutoSpace = true;
                setSpaceTimeStamp(true);
                TextEntryState.typedCharacter(' ', true);
            }
            // Add the word to the auto dictionary if it's not a known word
            mJustAutoAddedWord = false;
            if (index == 0) {
                mJustAutoAddedWord = checkAddToDictionaryWithAutoDictionary(mWord, AutoDictionary.AdditionType.Picked);
            }

            final boolean showingAddToDictionaryHint =
                    (!mJustAutoAddedWord)
                    && index == 0
                    && (mQuickFixes || mShowSuggestions)
                    && (!mSuggest.isValidWord(suggestion))// this is for the case that the word was auto-added upon picking
                    && (!mSuggest.isValidWord(suggestion.toString().toLowerCase(getCurrentKeyboard().getLocale())));

            if (showingAddToDictionaryHint) {
                TextEntryState.acceptedSuggestionAddedToDictionary();
                if (mCandidateView != null) mCandidateView.showAddToDictionaryHint(suggestion);
            } else if (!TextUtils.isEmpty(mCommittedWord) && !mJustAutoAddedWord) {
                //showing next-words if:
                //showingAddToDictionaryHint == false, we most likely do not have a next-word suggestion! The committed word is not in the dictionary
                //mJustAutoAddedWord == false, we most likely do not have a next-word suggestion for a newly added word.
                setSuggestions(mSuggest.getNextSuggestions(mCommittedWord, mWord.isAllUpperCase()), false, false, false);
                mWord.setFirstCharCapitalized(false);
            }
        } finally {
            if (ic != null) {
                ic.endBatchEdit();
            }
        }
    }

    /**
     * Commits the chosen word to the text field and saves it for later
     * retrieval.
     *
     * @param suggestion the suggestion picked by the user to be committed to the text
     *                   field
     * @param correcting whether this is due to a correction of an existing word.
     */
    private CharSequence pickSuggestion(CharSequence suggestion, boolean correcting) {
        if (mShiftKeyState.isLocked()) {
            suggestion = suggestion.toString().toUpperCase(getCurrentKeyboard().getLocale());
        } else if (preferCapitalization() || (mKeyboardSwitcher.isAlphabetMode() && mShiftKeyState.isActive())) {
            suggestion = Character.toUpperCase(suggestion.charAt(0)) + suggestion.subSequence(1, suggestion.length()).toString();
        }

        mWord.setPreferredWord(suggestion);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (correcting) {
                AnyApplication.getDeviceSpecific().commitCorrectionToInputConnection(ic, mGlobalCursorPosition - mWord.getTypedWord().length(), mWord.getTypedWord(), mWord.getPreferredWord());
                // and drawing pop-out text
                mInputView.popTextOutOfKey(mWord.getPreferredWord());
            } else {
                ic.commitText(suggestion, 1);
            }
        }
        mPredicting = false;
        mCommittedLength = suggestion.length();
        mCommittedWord = suggestion;
        mUndoCommitCursorPosition = UNDO_COMMIT_WAITING_TO_RECORD_POSITION;

        clearSuggestions();

        return suggestion;
    }

    private boolean isCursorTouchingWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return false;

        CharSequence toLeft = ic.getTextBeforeCursor(1, 0);
        // It is not exactly clear to me why, but sometimes, although I request
        // 1 character, I get
        // the entire text. This causes me to incorrectly detect restart
        // suggestions...
        if (!TextUtils.isEmpty(toLeft) && toLeft.length() == 1 && !isWordSeparator(toLeft.charAt(0))) {
            return true;
        }

        CharSequence toRight = ic.getTextAfterCursor(1, 0);
        return (!TextUtils.isEmpty(toRight)) &&
                (toRight.length() == 1) &&
                (!isWordSeparator(toRight.charAt(0)));
    }

    public void revertLastWord(boolean deleteChar) {
        final int length = mWord.length();
        if (!mPredicting && length > 0) {
            mAutoCorrectOn = false;
            final CharSequence typedWord = mWord.getTypedWord();
            final InputConnection ic = getCurrentInputConnection();
            mPredicting = true;
            mUndoCommitCursorPosition = UNDO_COMMIT_NONE;
            ic.beginBatchEdit();
            if (deleteChar)
                ic.deleteSurroundingText(1, 0);
            int toDelete = mCommittedLength;
            CharSequence toTheLeft = ic.getTextBeforeCursor(mCommittedLength, 0);
            if (toTheLeft != null && toTheLeft.length() > 0 && isWordSeparator(toTheLeft.charAt(0))) {
                toDelete--;
            }
            ic.deleteSurroundingText(toDelete, 0);
            ic.setComposingText(typedWord/* mComposing */, 1);
            TextEntryState.backspace();
            ic.endBatchEdit();
            postUpdateSuggestions(-1);
            if (mJustAutoAddedWord) {
                removeFromUserDictionary(typedWord.toString());
            }
            mInputView.revertPopTextOutOfKey();
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    private boolean isSentenceSeparator(int code) {
        return mSentenceSeparators.get(code, false);
    }

    private boolean isWordSeparator(int code) {
        return (!isAlphabet(code));
    }

    public boolean preferCapitalization() {
        return mWord.isFirstCharCapitalized();
    }

    private void nextAlterKeyboard(EditorInfo currentEditorInfo) {
        mKeyboardSwitcher.nextAlterKeyboard(currentEditorInfo);

        Log.d(TAG, "nextAlterKeyboard: Setting next keyboard to: %s", getCurrentKeyboard().getKeyboardName());
    }

    private void nextKeyboard(EditorInfo currentEditorInfo, KeyboardSwitcher.NextKeyboardType type) {
        // in numeric keyboards, the LANG key will go back to the original
        // alphabet keyboard-
        // so no need to look for the next keyboard, 'mLastSelectedKeyboard'
        // holds the last
        // keyboard used.
        mKeyboardSwitcher.nextKeyboard(currentEditorInfo, type);
        setKeyboardFinalStuff();
    }

    private static void fillSeparatorsSparseArray(SparseBooleanArray sparseBooleanArray, char[] chars) {
        sparseBooleanArray.clear();
        for (char separator : chars) sparseBooleanArray.put(separator, true);
    }

    private void setKeyboardFinalStuff() {
        mShiftKeyState.reset();
        mControlKeyState.reset();
        // changing dictionary
        setDictionariesForCurrentKeyboard();
        // Notifying if needed
        setKeyboardStatusIcon();
        postUpdateSuggestions();
        updateShiftStateNow();
    }

    @Override
    public void onSwipeRight( boolean twoFingersGesture) {
        final int keyCode;
        if (mFirstDownKeyCode == KeyCodes.DELETE) {
            keyCode = KeyCodes.DELETE_WORD;
        } else {
            keyCode = mAskPrefs.getGestureSwipeRightKeyCode(mFirstDownKeyCode == KeyCodes.SPACE, twoFingersGesture);
        }
        Log.d(TAG, "onSwipeRight with first-down " + mFirstDownKeyCode + ((twoFingersGesture) ? " + two-fingers" : "") + " => code " + keyCode);
        if (keyCode != 0) mSwitchAnimator.doSwitchAnimation(AnimationType.SwipeRight, keyCode);
    }

    @Override
    public void onSwipeLeft(boolean twoFingersGesture) {
        final int keyCode;
        if (mFirstDownKeyCode == KeyCodes.DELETE) {
            keyCode = KeyCodes.DELETE_WORD;
        } else {
            keyCode = mAskPrefs.getGestureSwipeLeftKeyCode(mFirstDownKeyCode == KeyCodes.SPACE, twoFingersGesture);
        }
        Log.d(TAG, "onSwipeLeft with first-down " + mFirstDownKeyCode + ((twoFingersGesture) ? " + two-fingers" : "") + " => code " + keyCode);
        if (keyCode != 0) mSwitchAnimator.doSwitchAnimation(AnimationType.SwipeLeft, keyCode);
    }

    @Override
    public void onSwipeDown() {
        final int keyCode = mAskPrefs.getGestureSwipeDownKeyCode();
        Log.d(TAG, "onSwipeDown => code " + keyCode);
        if (keyCode != 0) onKey(keyCode, null, -1, new int[]{keyCode}, false/*not directly pressed the UI key*/);
    }

    @Override
    public void onSwipeUp() {
        final int keyCode = mAskPrefs.getGestureSwipeUpKeyCode(mFirstDownKeyCode == KeyCodes.SPACE);
        Log.d(TAG, "onSwipeUp with first-down " + mFirstDownKeyCode + " => code " + keyCode);
        if (keyCode != 0) onKey(keyCode, null, -1, new int[]{keyCode}, false/*not directly pressed the UI key*/);
    }

    @Override
    public void onPinch() {
        final int keyCode = mAskPrefs.getGesturePinchKeyCode();
        Log.d(TAG, "onPinch => code %d", keyCode);
        if (keyCode != 0) onKey(keyCode, null, -1, new int[]{keyCode}, false/*not directly pressed the UI key*/);
    }

    @Override
    public void onSeparate() {
        final int keyCode = mAskPrefs.getGestureSeparateKeyCode();
        Log.d(TAG, "onSeparate => code %d", keyCode);
        if (keyCode != 0) onKey(keyCode, null, -1, new int[]{keyCode}, false/*not directly pressed the UI key*/);
    }

    @Override
    public void onFirstDownKey(int primaryCode) {
        mFirstDownKeyCode = primaryCode;
    }

    private void sendKeyDown(InputConnection ic, int key) {
        if (ic != null) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
    }

    private void sendKeyUp(InputConnection ic, int key) {
        if (ic != null) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    public void onPress(int primaryCode) {
        if (mArrowSelectionState && (primaryCode != KeyCodes.ARROW_LEFT && primaryCode != KeyCodes.ARROW_RIGHT)) {
            mArrowSelectionState = false;
        }
        InputConnection ic = getCurrentInputConnection();
        if (mVibrationDuration > 0 && primaryCode != 0 && mVibrator != null) {
            try {
                mVibrator.vibrate(mVibrationDuration);
            } catch (Exception e) {
                Log.w(TAG, "Failed to interact with vibrator! Disabling for now.");
                mVibrationDuration = 0;
            }
        }

        if (primaryCode == KeyCodes.SHIFT) {
            mShiftKeyState.onPress();
            handleShift();
        } else {
            mShiftKeyState.onOtherKeyPressed();
        }

        if (primaryCode == KeyCodes.CTRL) {
            mControlKeyState.onPress();
            handleControl();
            sendKeyDown(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT (API 11 and up)
        } else {
            mControlKeyState.onOtherKeyPressed();
        }

        if (mSoundOn && (!mSilentMode) && primaryCode != 0) {
            final int keyFX;
            switch (primaryCode) {
                case 13:
                case KeyCodes.ENTER:
                    keyFX = AudioManager.FX_KEYPRESS_RETURN;
                    break;
                case KeyCodes.DELETE:
                    keyFX = AudioManager.FX_KEYPRESS_DELETE;
                    break;
                case KeyCodes.SPACE:
                    keyFX = AudioManager.FX_KEYPRESS_SPACEBAR;
                    break;
                default:
                    keyFX = AudioManager.FX_KEY_CLICK;
            }
            final float fxVolume;
            // creating scoop to make sure volume and maxVolume
            // are not used
            {
                final int volume;
                final int maxVolume;
                if (mSoundVolume > 0) {
                    volume = mSoundVolume;
                    maxVolume = 100;
                    fxVolume = ((float) volume) / ((float) maxVolume);
                } else {
                    fxVolume = -1.0f;
                }

            }

            mAudioManager.playSoundEffect(keyFX, fxVolume);
        }
    }

    public void onRelease(int primaryCode) {
        InputConnection ic = getCurrentInputConnection();
        if (primaryCode == KeyCodes.SHIFT) {
            mShiftKeyState.onRelease(mAskPrefs.getMultiTapTimeout());
            handleShift();
        } else {
            if (mShiftKeyState.onOtherKeyReleased()) {
                updateShiftStateNow();
            }
        }

        if (primaryCode == KeyCodes.CTRL) {
            sendKeyUp(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT
            mControlKeyState.onRelease(mAskPrefs.getMultiTapTimeout());
            handleControl();
        } else {
            mControlKeyState.onOtherKeyReleased();
        }
    }

    // update flags for silent mode
    public void updateRingerMode() {
        mSilentMode = (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);
    }

    private void loadSettings() {
        // Get the settings preferences
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);

        mVibrationDuration = Integer.parseInt(sp.getString(getString(R.string.settings_key_vibrate_on_key_press_duration), getString(R.string.settings_default_vibrate_on_key_press_duration)));

        mSoundOn = sp.getBoolean(getString(R.string.settings_key_sound_on),
                getResources().getBoolean(R.bool.settings_default_sound_on));
        if (mSoundOn) {
            Log.i(TAG, "Loading sounds effects from AUDIO_SERVICE due to configuration change.");
            try {
                mAudioManager.loadSoundEffects();
            } catch (SecurityException e) {
                //for unknown reason loadSoundEffects may throw SecurityException (happened on a HuaweiG750-U10/4.2.2).
                Log.w(TAG, "SecurityException swallowed. ", e);
                mSoundOn = false;
            }
        }
        // checking the volume
        boolean customVolume = sp.getBoolean("use_custom_sound_volume", false);
        int newVolume;
        if (customVolume) {
            newVolume = sp.getInt("custom_sound_volume", 0) + 1;
            Log.i(TAG, "Custom volume checked: " + newVolume + " out of 100");
        } else {
            Log.i(TAG, "Custom volume un-checked.");
            newVolume = -1;
        }
        mSoundVolume = newVolume;

        mShowKeyboardIconInStatusBar = sp.getBoolean(
                getString(R.string.settings_key_keyboard_icon_in_status_bar),
                getResources().getBoolean(R.bool.settings_default_keyboard_icon_in_status_bar));

        if (mShowKeyboardIconInStatusBar) {
            setKeyboardStatusIcon();
        } else {
            mInputMethodManager.hideStatusIcon(mImeToken);
        }

        mAutoCap = sp.getBoolean("auto_caps", true);

        mShowSuggestions = sp.getBoolean("candidates_on", true);
        if (!mShowSuggestions) {
            //no suggestions is needed, we'll release all dictionaries.
            closeDictionaries();
        }

        final String autoPickAggressiveness = sp.getString(
                getString(R.string.settings_key_auto_pick_suggestion_aggressiveness),
                getString(R.string.settings_default_auto_pick_suggestion_aggressiveness));

        final int calculatedCommonalityMaxLengthDiff;
        final int calculatedCommonalityMaxDistance;
        switch (autoPickAggressiveness) {
            case "none":
                calculatedCommonalityMaxLengthDiff = 0;
                calculatedCommonalityMaxDistance = 0;
                mAutoComplete = false;
                break;
            case "minimal_aggressiveness":
                calculatedCommonalityMaxLengthDiff = 1;
                calculatedCommonalityMaxDistance = 1;
                mAutoComplete = true;
                break;
            case "high_aggressiveness":
                calculatedCommonalityMaxLengthDiff = 3;
                calculatedCommonalityMaxDistance = 4;
                mAutoComplete = true;
                break;
            case "extreme_aggressiveness":
                calculatedCommonalityMaxLengthDiff = 5;
                calculatedCommonalityMaxDistance = 5;
                mAutoComplete = true;
                break;
            default:
                calculatedCommonalityMaxLengthDiff = 2;
                calculatedCommonalityMaxDistance = 3;
                mAutoComplete = true;
        }
        mAutoCorrectOn = mAutoComplete = mAutoComplete && mShowSuggestions;

        mQuickFixes = sp.getBoolean("quick_fix", true);

        mAllowSuggestionsRestart = sp.getBoolean(
                getString(R.string.settings_key_allow_suggestions_restart),
                getResources().getBoolean(R.bool.settings_default_allow_suggestions_restart));

        mSuggest.setCorrectionMode(mQuickFixes, mShowSuggestions,
                calculatedCommonalityMaxLengthDiff, calculatedCommonalityMaxDistance,
                sp.getInt(getString(R.string.settings_key_min_length_for_word_correction__), 2));

        mDoNotFlipQuickTextKeyAndPopupFunctionality = sp.getBoolean(
                getString(R.string.settings_key_do_not_flip_quick_key_codes_functionality),
                getResources().getBoolean(R.bool.settings_default_do_not_flip_quick_keys_functionality));

        mOverrideQuickTextText = sp.getString(getString(R.string.settings_key_emoticon_default_text), null);

        setInitialCondensedState(getResources().getConfiguration());
    }

    private void setDictionariesForCurrentKeyboard() {
        mSuggest.resetNextWordSentence();

        if (mPredictionOn) {
            mLastDictionaryRefresh = SystemClock.elapsedRealtime();
            // It null at the creation of the application.
            if ((mKeyboardSwitcher != null) && mKeyboardSwitcher.isAlphabetMode()) {
                AnyKeyboard currentKeyboard = mKeyboardSwitcher.getCurrentKeyboard();
                fillSeparatorsSparseArray(mSentenceSeparators, currentKeyboard.getSentenceSeparators());

                // if there is a mapping in the settings, we'll use that,
                // else we'll
                // return the default
                String mappingSettingsKey = getDictionaryOverrideKey(currentKeyboard);
                String defaultDictionary = currentKeyboard.getDefaultDictionaryLocale();
                String dictionaryValue = mPrefs.getString(mappingSettingsKey, null);

                final DictionaryAddOnAndBuilder dictionaryBuilder;

                if (dictionaryValue == null) {
                    dictionaryBuilder = ExternalDictionaryFactory.getDictionaryBuilderByLocale(
                            currentKeyboard.getDefaultDictionaryLocale(), getApplicationContext());
                } else {
                    Log.d(TAG, "Default dictionary '%s' for keyboard '%s' has been overridden to '%s'",
                            defaultDictionary, currentKeyboard.getKeyboardPrefId(), dictionaryValue);
                    dictionaryBuilder = ExternalDictionaryFactory.getDictionaryBuilderById(dictionaryValue, getApplicationContext());
                }

                mSuggest.setMainDictionary(getApplicationContext(), dictionaryBuilder);
                String localeForSupportingDictionaries = dictionaryBuilder != null ? dictionaryBuilder.getLanguage() : defaultDictionary;
                Dictionary userDictionary = mSuggest.getDictionaryFactory().createUserDictionary(getApplicationContext(), localeForSupportingDictionaries);
                mSuggest.setUserDictionary(userDictionary);

                mAutoDictionary = mSuggest.getDictionaryFactory().createAutoDictionary(getApplicationContext(), localeForSupportingDictionaries);
                mSuggest.setAutoDictionary(mAutoDictionary);
                mSuggest.setContactsDictionary(getApplicationContext(), mAskPrefs.useContactsDictionary());
            }
        }
    }

    private void launchSettings() {
        handleClose();
        Intent intent = new Intent();
        intent.setClass(AnySoftKeyboard.this, MainSettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void launchDictionaryOverriding() {
        final String dictionaryOverridingKey = getDictionaryOverrideKey(getCurrentKeyboard());
        final String dictionaryOverrideValue = mPrefs.getString(dictionaryOverridingKey, null);
        ArrayList<CharSequence> dictionaryIds = new ArrayList<>();
        ArrayList<CharSequence> dictionaries = new ArrayList<>();
        // null dictionary is handled as the default for the keyboard
        dictionaryIds.add(null);
        final String SELECTED = "\u2714 ";
        final String NOT_SELECTED = "- ";
        if (dictionaryOverrideValue == null)
            dictionaries.add(SELECTED + getString(R.string.override_dictionary_default));
        else
            dictionaries.add(NOT_SELECTED + getString(R.string.override_dictionary_default));
        // going over all installed dictionaries
        for (DictionaryAddOnAndBuilder dictionaryBuilder : ExternalDictionaryFactory.getAllAvailableExternalDictionaries(getApplicationContext())) {
            dictionaryIds.add(dictionaryBuilder.getId());
            String description;
            if (dictionaryOverrideValue != null
                    && dictionaryBuilder.getId()
                    .equals(dictionaryOverrideValue))
                description = SELECTED;
            else
                description = NOT_SELECTED;
            description += dictionaryBuilder.getName();
            if (!TextUtils.isEmpty(dictionaryBuilder.getDescription())) {
                description += " (" + dictionaryBuilder.getDescription() + ")";
            }
            dictionaries.add(description);
        }

        final CharSequence[] ids = new CharSequence[dictionaryIds.size()];
        final CharSequence[] items = new CharSequence[dictionaries.size()];
        dictionaries.toArray(items);
        dictionaryIds.toArray(ids);

        showOptionsDialogWithData(getString(R.string.override_dictionary_title, getCurrentKeyboard().getKeyboardName()), R.drawable.ic_settings_language,
                items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int position) {
                        di.dismiss();
                        Editor editor = mPrefs.edit();
                        if (position == 0) {
                            editor.remove(dictionaryOverridingKey);
                            showToastMessage(R.string.override_disabled, true);
                        } else {
                            CharSequence id = ids[position];
                            String selectedDictionaryId = (id == null) ? null : id.toString();
                            String selectedLanguageString = items[position].toString();
                            editor.putString(dictionaryOverridingKey, selectedDictionaryId);
                            showToastMessage(getString(R.string.override_enabled, selectedLanguageString), true);
                        }
                        editor.commit();
                        setDictionariesForCurrentKeyboard();
                    }
                });
    }

    private void showOptionsMenu() {
        showOptionsDialogWithData(getText(R.string.ime_name), R.drawable.ic_launcher,
                new CharSequence[]{
                        getText(R.string.ime_settings),
                        getText(R.string.override_dictionary),
                        getText(R.string.change_ime)},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int position) {
                        switch (position) {
                            case 0:
                                launchSettings();
                                break;
                            case 1:
                                launchDictionaryOverriding();
                                break;
                            case 2:
                                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker();
                                break;
                        }
                    }
                }
        );
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.orientation != mOrientation) {
            mOrientation = newConfig.orientation;
            setInitialCondensedState(newConfig);

            commitTyped(getCurrentInputConnection());

            mKeyboardSwitcher.flushKeyboardsCache();

            String sentenceSeparatorsForCurrentKeyboard = mKeyboardSwitcher.getCurrentKeyboardSentenceSeparators();
            if (sentenceSeparatorsForCurrentKeyboard == null) {
                mSentenceSeparators.clear();
            } else {
                fillSeparatorsSparseArray(mSentenceSeparators, sentenceSeparatorsForCurrentKeyboard.toCharArray());
            }
        }

        super.onConfigurationChanged(newConfig);
    }

    private void setInitialCondensedState(Configuration newConfig) {
        final String defaultCondensed = mAskPrefs.getInitialKeyboardCondenseState();
        mKeyboardInCondensedMode = CondenseType.None;
        switch (defaultCondensed) {
            case "split_always":
                mKeyboardInCondensedMode = CondenseType.Split;
                break;
            case "split_in_landscape":
                if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    mKeyboardInCondensedMode = CondenseType.Split;
                else
                    mKeyboardInCondensedMode = CondenseType.None;
                break;
            case "compact_right_always":
                mKeyboardInCondensedMode = CondenseType.CompactToRight;
                break;
            case "compact_left_always":
                mKeyboardInCondensedMode = CondenseType.CompactToLeft;
                break;
        }
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Log.d(TAG, "onSharedPreferenceChanged (key '%s')", key);
        AnyApplication.requestBackupToCloud();

        loadSettings();

        if (key.startsWith(KeyboardAddOnAndBuilder.KEYBOARD_PREF_PREFIX) ||
                key.startsWith("dictionary_") ||
                key.equals(getString(R.string.settings_key_active_quick_text_key)) ||
                key.equals(getString(R.string.settings_key_ext_kbd_bottom_row_key)) ||
                key.equals(getString(R.string.settings_key_ext_kbd_top_row_key)) ||
                key.equals(getString(R.string.settings_key_ext_kbd_ext_ketboard_key)) ||
                key.equals(getString(R.string.settings_key_ext_kbd_hidden_bottom_row_key)) ||
                key.equals(getString(R.string.settings_key_keyboard_theme_key)) ||
                key.equals("zoom_factor_keys_in_portrait") ||
                key.equals("zoom_factor_keys_in_landscape") ||
                key.equals(getString(R.string.settings_key_smiley_icon_on_smileys_key)) ||
                key.equals(getString(R.string.settings_key_long_press_timeout)) ||
                key.equals(getString(R.string.settings_key_multitap_timeout)) ||
                key.equals(getString(R.string.settings_key_default_split_state))) {
            //this will recreate the keyboard view AND flush the keyboards cache.
            resetKeyboardView(true);
        }
    }

    public void deleteLastCharactersFromInput(int countToDelete) {
        if (countToDelete == 0)
            return;

        final int currentLength = mWord.length();
        boolean shouldDeleteUsingCompletion;
        if (currentLength > 0) {
            shouldDeleteUsingCompletion = true;
            if (currentLength > countToDelete) {
                int deletesLeft = countToDelete;
                while (deletesLeft > 0) {
                    mWord.deleteLast();
                    deletesLeft--;
                }
            } else {
                mWord.reset();
            }
        } else {
            shouldDeleteUsingCompletion = false;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (mPredictionOn && shouldDeleteUsingCompletion) {
                ic.setComposingText(mWord.getTypedWord()/* mComposing */, 1);
            } else {
                ic.deleteSurroundingText(countToDelete, 0);
            }
        }
    }

    public void showToastMessage(@StringRes int resId, boolean forShortTime) {
        CharSequence text = getResources().getText(resId);
        showToastMessage(text, forShortTime);
    }

    private void showToastMessage(CharSequence text, boolean forShortTime) {
        int duration = forShortTime ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
        Toast.makeText(this.getApplication(), text, duration).show();
    }

    @Override
    public void onLowMemory() {
        Log.w(TAG, "The OS has reported that it is low on memory!. I'll try to clear some cache.");
        mKeyboardSwitcher.onLowMemory();
        super.onLowMemory();
    }

    public WordComposer getCurrentWord() {
        return mWord;
    }

    /**
     * Override this to control when the soft input area should be shown to the
     * user. The default implementation only shows the input view when there is
     * no hard keyboard or the keyboard is hidden. If you change what this
     * returns, you will need to call {@link #updateInputViewShown()} yourself
     * whenever the returned value may have changed to have it re-evalauted and
     * applied. This needs to be re-coded for Issue 620
     */
    @SuppressLint("MissingSuperCall")
    @Override
    public boolean onEvaluateInputViewShown() {
        Configuration config = getResources().getConfiguration();
        return  config.keyboard == Configuration.KEYBOARD_NOKEYS ||
                config.hardKeyboardHidden == Configuration.KEYBOARDHIDDEN_YES;
    }

    public void onCancel() {
        hideWindow();
    }

    public void resetKeyboardView(boolean recreateView) {
        handleClose();
        if (recreateView) {
            // also recreate keyboard view
            setInputView(onCreateInputView());
            setCandidatesView(onCreateCandidatesView());
            setCandidatesViewShown(false);
        }
    }

    private void updateShiftStateNow() {
        final InputConnection ic = getCurrentInputConnection();
        EditorInfo ei = getCurrentInputEditorInfo();
        final int caps;
        if (mAutoCap && ic != null && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
            caps = ic.getCursorCapsMode(ei.inputType);
        } else {
            caps = 0;
        }
        final boolean inputSaysCaps = caps != 0;
        Log.d(TAG, "shift updateShiftStateNow inputSaysCaps=%s", inputSaysCaps);
        mShiftKeyState.setActiveState(inputSaysCaps);
        handleShift();
    }

    /*package*/ void closeDictionaries() {
        mSuggest.closeDictionaries();
        //ensuring that next time the dictionaries will be refreshed
        mLastDictionaryRefresh = -1;
    }
}
