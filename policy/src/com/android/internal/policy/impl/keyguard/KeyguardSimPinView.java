/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl.keyguard;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.telephony.ITelephony;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.PasswordEntryKeyboardHelper;
import com.android.internal.widget.PasswordEntryKeyboardView;
import com.android.internal.R;

import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

/**
 * Displays a dialer like interface to unlock the SIM PIN.
 */
public class KeyguardSimPinView extends LinearLayout
        implements KeyguardSecurityView, OnEditorActionListener, TextWatcher {

    private EditText mPinEntry;
    private ProgressDialog mSimUnlockProgressDialog = null;
    private KeyguardSecurityCallback mCallback;
    private PasswordEntryKeyboardView mKeyboardView;
    private PasswordEntryKeyboardHelper mKeyboardHelper;
    private LockPatternUtils mLockPatternUtils;
    private SecurityMessageDisplay mSecurityMessageDisplay;

    private volatile boolean mSimCheckInProgress;

    public KeyguardSimPinView(Context context) {
        this(context, null);
    }

    public KeyguardSimPinView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mPinEntry = (EditText) findViewById(R.id.sim_pin_entry);
        mPinEntry.setOnEditorActionListener(this);
        mPinEntry.addTextChangedListener(this);

        mKeyboardView = (PasswordEntryKeyboardView) findViewById(R.id.keyboard);
        mKeyboardHelper = new PasswordEntryKeyboardHelper(mContext, mKeyboardView, this, false,
                new int[] {
                R.xml.kg_password_kbd_numeric,
                com.android.internal.R.xml.password_kbd_qwerty,
                com.android.internal.R.xml.password_kbd_qwerty_shifted,
                com.android.internal.R.xml.password_kbd_symbols,
                com.android.internal.R.xml.password_kbd_symbols_shift
                });
        mKeyboardHelper.setKeyboardMode(PasswordEntryKeyboardHelper.KEYBOARD_MODE_NUMERIC);
        mKeyboardHelper.setEnableHaptics(mLockPatternUtils.isTactileFeedbackEnabled());

        final View deleteButton = findViewById(R.id.delete_button);
        if (deleteButton != null) {
            deleteButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    mKeyboardHelper.handleBackspace();
                }
            });
        }

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        mSecurityMessageDisplay.setTimeout(0);
        reset();
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        return mPinEntry.requestFocus(direction, previouslyFocusedRect);
    }

    public void reset() {
        // start fresh
        mSecurityMessageDisplay.setMessage(R.string.kg_sim_pin_instructions, true);

        // make sure that the number of entered digits is consistent when we
        // erase the SIM unlock code, including orientation changes.
        mPinEntry.setText("");
        mPinEntry.requestFocus();
    }

    @Override
    public void showUsabilityHint() {
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPin extends Thread {
        private final String mPin;

        protected CheckSimPin(String pin) {
            mPin = pin;
        }

        abstract void onSimCheckResponse(boolean success);

        @Override
        public void run() {
            try {
                final boolean result = ITelephony.Stub.asInterface(ServiceManager
                        .checkService("phone")).supplyPin(mPin);
                post(new Runnable() {
                    public void run() {
                        onSimCheckResponse(result);
                    }
                });
            } catch (RemoteException e) {
                post(new Runnable() {
                    public void run() {
                        onSimCheckResponse(false);
                    }
                });
            }
        }
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        // Check if this was the result of hitting the enter key
        mCallback.userActivity(KeyguardViewManager.DIGIT_PRESS_WAKE_MILLIS);
        if (event.getAction() == MotionEvent.ACTION_DOWN && (
                actionId == EditorInfo.IME_NULL
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT)) {
            checkPin();
            return true;
        }
        return false;
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mContext);
            mSimUnlockProgressDialog.setMessage(
                    mContext.getString(R.string.kg_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            if (!(mContext instanceof Activity)) {
                mSimUnlockProgressDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            }
        }
        return mSimUnlockProgressDialog;
    }

    private void checkPin() {
        if (mPinEntry.getText().length() < 4) {
            // otherwise, display a message to the user, and don't submit.
            mSecurityMessageDisplay.setMessage(R.string.kg_invalid_sim_pin_hint, true);
            mPinEntry.setText("");
            mCallback.userActivity(0);
            return;
        }

        getSimUnlockProgressDialog().show();

        if (!mSimCheckInProgress) {
            mSimCheckInProgress = true; // there should be only one
            new CheckSimPin(mPinEntry.getText().toString()) {
                void onSimCheckResponse(final boolean success) {
                    post(new Runnable() {
                        public void run() {
                            if (mSimUnlockProgressDialog != null) {
                                mSimUnlockProgressDialog.hide();
                            }
                            if (success) {
                                // before closing the keyguard, report back that the sim is unlocked
                                // so it knows right away.
                                KeyguardUpdateMonitor.getInstance(getContext()).reportSimUnlocked();
                                mCallback.dismiss(true);
                            } else {
                                mSecurityMessageDisplay.setMessage
                                    (R.string.kg_password_wrong_pin_code, true);
                                mPinEntry.setText("");
                            }
                            mCallback.userActivity(0);
                            mSimCheckInProgress = false;
                        }
                    });
                }
            }.start();
        }
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    public boolean needsInput() {
        return false; // This view provides its own keypad
    }

    public void onPause() {

    }

    public void onResume() {
        reset();
    }

    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (mCallback != null) {
            mCallback.userActivity(KeyguardViewManager.DIGIT_PRESS_WAKE_MILLIS);
        }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

}
