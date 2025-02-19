package com.anysoftkeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.inputmethod.EditorInfo;

import com.anysoftkeyboard.base.dictionaries.Dictionary;
import com.anysoftkeyboard.dictionaries.DictionaryAddOnAndBuilder;
import com.anysoftkeyboard.dictionaries.UserDictionary;
import com.menny.android.anysoftkeyboard.AskGradleTestRunner;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ServiceController;

@RunWith(AskGradleTestRunner.class)
public class AnySoftKeyboardDictionaryEnablingTest {

    private static final String[] DICTIONATY_WORDS = new String[] {
            "high", "hello", "menny", "AnySoftKeyboard", "keyboard", "google", "low"
    };
    private ServiceController<TestableAnySoftKeyboard> mAnySoftKeyboardController;
    private TestableAnySoftKeyboard mAnySoftKeyboardUnderTest;

    @Before
    public void setUp() throws Exception {
        mAnySoftKeyboardController = Robolectric.buildService(TestableAnySoftKeyboard.class);
        mAnySoftKeyboardUnderTest = mAnySoftKeyboardController.attach().create().get();

        Assert.assertNotNull(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setCorrectionMode(true, true, 2, 3, 2);

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        UserDictionary userDictionary = new UserDictionary(RuntimeEnvironment.application, "en");
        userDictionary.loadDictionary();
        for (int wordIndex = 0; wordIndex<DICTIONATY_WORDS.length; wordIndex++) {
            userDictionary.addWord(DICTIONATY_WORDS[wordIndex], DICTIONATY_WORDS.length - wordIndex);
        }
        userDictionary.close();
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testDictionariesCreatedForText() {
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));

        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));

        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setUserDictionary(Mockito.isNotNull(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setAutoDictionary(Mockito.isNotNull(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setContactsDictionary(Mockito.isNotNull(Context.class), Mockito.eq(true));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setMainDictionary(Mockito.isNotNull(Context.class), Mockito.isNotNull(DictionaryAddOnAndBuilder.class));
        Assert.assertTrue(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesRefreshedAfter30Seconds() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setUserDictionary(Mockito.isNotNull(Dictionary.class));
        Assert.assertTrue(mAnySoftKeyboardUnderTest.isPredictionOn());

        ShadowSystemClock.sleep(10);

        mAnySoftKeyboardUnderTest.onFinishInputView(false);
        mAnySoftKeyboardUnderTest.onFinishInput();

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Assert.assertTrue(mAnySoftKeyboardUnderTest.isPredictionOn());

        ShadowSystemClock.sleep(1 + 30*1000);

        mAnySoftKeyboardUnderTest.onFinishInputView(false);
        mAnySoftKeyboardUnderTest.onFinishInput();

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setUserDictionary(Mockito.isNotNull(Dictionary.class));
        Assert.assertTrue(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForTextWithOutViewCreated() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForPassword() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfo(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT+EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForVisiblePassword() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfo(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT+EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForWebPassword() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfo(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT+EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForUriInput() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfo(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT+EditorInfo.TYPE_TEXT_VARIATION_URI);
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForEmailInput() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfo(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT+EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForWebEmailInput() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfo(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT+EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForAutoComplete() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        editorInfo.inputType += EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE;
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesNotCreatedForNoSuggestions() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        editorInfo.inputType += EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setAutoDictionary(Mockito.any(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setContactsDictionary(Mockito.any(Context.class), Mockito.anyBoolean());
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setMainDictionary(Mockito.any(Context.class), Mockito.any(DictionaryAddOnAndBuilder.class));
        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDictionariesResetForPassword() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);
        Assert.assertTrue(mAnySoftKeyboardUnderTest.isPredictionOn());

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        mAnySoftKeyboardUnderTest.onFinishInputView(true);
        mAnySoftKeyboardUnderTest.onFinishInput();
        final EditorInfo editorInfoPassword = TestableAnySoftKeyboard.createEditorInfo(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT+EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        mAnySoftKeyboardUnderTest.onStartInput(editorInfoPassword, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfoPassword, false);

        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testReleasingAllDictionariesIfPrefsSetToNoSuggestions() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);
        Assert.assertTrue(mAnySoftKeyboardUnderTest.isPredictionOn());

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        mAnySoftKeyboardUnderTest.onFinishInputView(true);
        mAnySoftKeyboardUnderTest.onFinishInput();

        SharedPrefsHelper.setPrefsValue("candidates_on", false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).closeDictionaries();
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setCorrectionMode(Mockito.anyBoolean(), Mockito.eq(false), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());

        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Assert.assertFalse(mAnySoftKeyboardUnderTest.isPredictionOn());

        mAnySoftKeyboardUnderTest.onFinishInputView(true);
        mAnySoftKeyboardUnderTest.onFinishInput();

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        SharedPrefsHelper.setPrefsValue("candidates_on", true);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setCorrectionMode(Mockito.anyBoolean(), Mockito.eq(true), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());

        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);

        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setUserDictionary(Mockito.isNotNull(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setAutoDictionary(Mockito.isNotNull(Dictionary.class));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setContactsDictionary(Mockito.isNotNull(Context.class), Mockito.eq(true));
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setMainDictionary(Mockito.isNotNull(Context.class), Mockito.isNotNull(DictionaryAddOnAndBuilder.class));
        Assert.assertTrue(mAnySoftKeyboardUnderTest.isPredictionOn());
    }

    @Test
    public void testDoesNotCloseDictionaryIfInputRestartsQuickly() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);
        //setting the dictionary
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setUserDictionary(Mockito.isNotNull(Dictionary.class));

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        mAnySoftKeyboardUnderTest.onFinishInputView(true);
        mAnySoftKeyboardUnderTest.onFinishInput();
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).closeDictionaries();
        //waiting a bit
        ShadowSystemClock.sleep(10);
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).closeDictionaries();
        //restarting the input
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest(), Mockito.never()).setUserDictionary(Mockito.any(Dictionary.class));
    }

    @Test
    public void testDoesCloseDictionaryIfInputRestartsSlowly() {
        final EditorInfo editorInfo = TestableAnySoftKeyboard.createEditorInfoTextWithSuggestions();
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onCreateInputView();
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);
        //setting the dictionary
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setUserDictionary(Mockito.isNotNull(Dictionary.class));

        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());

        mAnySoftKeyboardUnderTest.onFinishInputView(true);
        mAnySoftKeyboardUnderTest.onFinishInput();
        //waiting a long time
        ShadowSystemClock.sleep(1000);
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).closeDictionaries();
        Mockito.reset(mAnySoftKeyboardUnderTest.getSpiedSuggest());
        //restarting the input
        mAnySoftKeyboardUnderTest.onStartInput(editorInfo, false);
        mAnySoftKeyboardUnderTest.onStartInputView(editorInfo, false);
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setUserDictionary(Mockito.any(Dictionary.class));
    }

    @Test
    public void testSettingCorrectModeFromPrefs() {
        SharedPrefsHelper.setPrefsValue("quick_fix", false);
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setCorrectionMode(false, true, 2, 3, 2);
        SharedPrefsHelper.setPrefsValue("settings_key_auto_pick_suggestion_aggressiveness", "minimal_aggressiveness");
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setCorrectionMode(false, true, 1, 1, 2);
        SharedPrefsHelper.setPrefsValue("settings_key_min_length_for_word_correction__", 4);
        Mockito.verify(mAnySoftKeyboardUnderTest.getSpiedSuggest()).setCorrectionMode(false, true, 1, 1, 4);
    }
}