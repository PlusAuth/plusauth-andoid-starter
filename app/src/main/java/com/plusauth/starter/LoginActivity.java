package com.plusauth.starter;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;

public final class LoginActivity extends AppCompatActivity {

    private static final String EXTRA_FAILED = "failed";
    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        authManager = new AuthManager(this, this::onAuthReady);

        setContentView(R.layout.activity_login);

        findViewById(R.id.retry).setOnClickListener((View view) ->
            authManager.retry(this));
        findViewById(R.id.start_auth).setOnClickListener((View view) -> startAuth());

        if (getIntent().getBooleanExtra(EXTRA_FAILED, false)) {
            displayAuthCancelled();
        }

        showLoading("Initializing");
    }

    @Override
    protected void onStart() {
        super.onStart();
        authManager.start();

        if (authManager.isAuthorized()) {
            startActivity(new Intent(this, TokenActivity.class));
            finish();
            return;
        }

        authManager.initializeAppAuth(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        authManager.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        authManager.destroy();
        authManager = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_CANCELED) {
            displayAuthCancelled();
        } else {
            Intent intent = new Intent(this, TokenActivity.class);
            intent.putExtras(data.getExtras());
            startActivity(intent);
        }
    }

    void startAuth() {
        showLoading("Making authorization request");

        authManager.authenticate(this::startActivityForResult);
    }

    private void displayAuthCancelled() {
        Snackbar.make(findViewById(R.id.coordinator),
            "Authorization canceled",
            Snackbar.LENGTH_SHORT)
            .show();
    }

    public void showLoading(@Nullable String message) {
        runOnUiThread(() -> {
            findViewById(R.id.loading_container).setVisibility(View.VISIBLE);
            findViewById(R.id.auth_container).setVisibility(View.GONE);
            findViewById(R.id.error_container).setVisibility(View.GONE);

            if (message != null) {
                ((TextView) findViewById(R.id.loading_description)).setText(message);
            }
        });
    }

    public void onAuthReady() {
        runOnUiThread(() -> {
            findViewById(R.id.error_container).setVisibility(View.GONE);
            findViewById(R.id.loading_container).setVisibility(View.GONE);
            findViewById(R.id.auth_container).setVisibility(View.VISIBLE);
        });
    }

}
