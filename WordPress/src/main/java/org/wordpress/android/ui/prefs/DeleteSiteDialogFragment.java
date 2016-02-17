package org.wordpress.android.ui.prefs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.util.UrlUtils;

public class DeleteSiteDialogFragment extends DialogFragment implements TextWatcher, DialogInterface.OnShowListener {
    public static final String SITE_DOMAIN_KEY = "site-domain";

    private AlertDialog mDeleteSiteDialog;
    private EditText mUrlConfirmation;
    private Button mDeleteButton;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        configureAlertViewBuilder(builder);

        mDeleteSiteDialog = builder.create();
        mDeleteSiteDialog.setOnShowListener(this);

        return mDeleteSiteDialog;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (isUrlConfirmationTextValid()) {
            mDeleteButton.setEnabled(true);
        } else {
            mDeleteButton.setEnabled(false);
        }
    }

    @Override
    public void onShow(DialogInterface dialog) {
        mDeleteButton = mDeleteSiteDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        mDeleteButton.setEnabled(false);
    }

    private void configureAlertViewBuilder(AlertDialog.Builder builder) {
        builder.setTitle(R.string.delete_site_question);
        String url = WordPress.getCurrentBlog().getHomeURL();
        String deletePrompt = String.format(getString(R.string.confirm_delete_site_prompt), UrlUtils.getHost(url));
        builder.setMessage(deletePrompt);

        configureUrlConfirmation(builder);
        configureButtons(builder);
    }

    private void configureButtons(AlertDialog.Builder builder) {
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
        builder.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Fragment target = getTargetFragment();
                if (target != null) {
                    target.onActivityResult(getTargetRequestCode(), Activity.RESULT_OK, null);
                }

                dismiss();
            }
        });
    }

    private void configureUrlConfirmation(AlertDialog.Builder builder) {
        View view = getActivity().getLayoutInflater().inflate(R.layout.delete_site_dialog, null);
        mUrlConfirmation = (EditText) view.findViewById(R.id.url_confirmation);
        setSiteDomainHint();
        mUrlConfirmation.addTextChangedListener(this);
        builder.setView(view);
    }

    private void setSiteDomainHint() {
        Bundle args = getArguments();
        String siteDomain = getString(R.string.delete).toLowerCase();
        if (args != null) {
            siteDomain = args.getString(SITE_DOMAIN_KEY);
        }
        mUrlConfirmation.setHint(siteDomain);
    }

    private boolean isUrlConfirmationTextValid() {
        String confirmationText = mUrlConfirmation.getText().toString().trim().toLowerCase();
        String hintText = mUrlConfirmation.getHint().toString().toLowerCase();

        return confirmationText.equals(hintText);
    }
}
