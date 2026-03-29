package com.termux.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.ssh.RemoteFile;
import com.termux.app.ssh.RemoteFileListAdapter;
import com.termux.app.ssh.RemoteFileLister;
import com.termux.app.ssh.RemoteFileOperator;
import com.termux.app.ssh.SSHConnectionInfo;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.theme.NightMode;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.termux.interact.TextInputDialogUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Activity for browsing remote files via SSH ControlMaster connection.
 *
 * Displays a directory listing from a remote SSH server with:
 * - Breadcrumb navigation showing current path
 * - Click to enter subdirectories
 * - Back button to navigate to parent directories
 * - Refresh button to reload current directory
 *
 * Requires SSHConnectionInfo passed via Intent extras to establish
 * connection through existing ControlMaster socket.
 */
public class RemoteFileBrowserActivity extends AppCompatActivity {

    private static final String LOG_TAG = "RemoteFileBrowserActivity";

    /** Intent extra key for SSH connection info */
    public static final String EXTRA_CONNECTION_INFO = "connection_info";

    /** Intent extra key for initial remote path */
    public static final String EXTRA_INITIAL_PATH = "initial_path";

    /** Default initial path if not specified */
    private static final String DEFAULT_INITIAL_PATH = "/";

    /** Current SSH connection info */
    private SSHConnectionInfo mConnectionInfo;

    /** Current remote directory path */
    private String mCurrentPath;

    /** Stack of visited paths for back navigation */
    private final Stack<String> mPathStack = new Stack<>();

    /** ListView for displaying files */
    private ListView mFileListView;

    /** Adapter for file list */
    private RemoteFileListAdapter mAdapter;

    /** Empty state view */
    private TextView mEmptyView;

    /** Breadcrumb path layout container */
    private LinearLayout mBreadcrumbPathLayout;

    /** Back button */
    private ImageButton mBackButton;

    /** Refresh button */
    private View mRefreshButton;

    /** Loading indicator */
    private ProgressBar mLoadingIndicator;

    /** Main content view (hidden during loading) */
    private View mContentContainer;

    /** Handler for UI updates from background threads */
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    /** Flag indicating if a load operation is in progress */
    private boolean mIsLoading = false;

    /** Flag indicating if activity is still active */
    private boolean mIsActive = false;

    /** Currently selected file for context menu operations */
    private RemoteFile mSelectedFile = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Apply night mode theme
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_remote_file_browser);

        // Set up toolbar with back button
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        // Initialize views
        initializeViews();

        // Parse intent extras
        if (!parseIntentExtras()) {
            finish();
            return;
        }

        // Set up click listeners
        setupClickListeners();

        // Mark activity as active
        mIsActive = true;

        // Load initial directory
        loadDirectory(mCurrentPath);

        Logger.logDebug(LOG_TAG, "Activity created for connection: " + mConnectionInfo.toString());
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsActive = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsActive = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mIsActive = false;
        mMainThreadHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    /**
     * Initialize view references from layout.
     */
    private void initializeViews() {
        mFileListView = findViewById(R.id.file_list);
        mEmptyView = findViewById(R.id.empty_view);
        mBreadcrumbPathLayout = findViewById(R.id.breadcrumb_path_layout);
        mBackButton = findViewById(R.id.back_button);
        mRefreshButton = findViewById(R.id.refresh_button);

        // Create loading indicator programmatically
        mLoadingIndicator = findViewById(R.id.loading_indicator);
        mContentContainer = findViewById(R.id.root_layout);

        // Set empty view for ListView
        mFileListView.setEmptyView(mEmptyView);

        // Create adapter with empty list
        mAdapter = new RemoteFileListAdapter(this, new ArrayList<>());
        mFileListView.setAdapter(mAdapter);

        // Register for context menu (long press)
        registerForContextMenu(mFileListView);
    }

    /**
     * Parse SSH connection info and initial path from Intent extras.
     *
     * @return true if parsing succeeded, false if required extras missing
     */
    private boolean parseIntentExtras() {
        Intent intent = getIntent();
        if (intent == null) {
            Logger.logError(LOG_TAG, "No intent provided");
            showError(getString(R.string.error_not_connected));
            return false;
        }

        // Get SSH connection info (must be passed from SSH session activity)
        // The connection info should be provided as a Serializable or Parcelable
        // For now, we expect socket path components to be passed separately
        String socketPath = intent.getStringExtra("socket_path");
        String user = intent.getStringExtra("user");
        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 22);

        if (socketPath == null || socketPath.isEmpty()) {
            // Try to get serialized connection info object
            Serializable connectionInfoSerial = intent.getSerializableExtra(EXTRA_CONNECTION_INFO);
            if (connectionInfoSerial instanceof SSHConnectionInfo) {
                mConnectionInfo = (SSHConnectionInfo) connectionInfoSerial;
            } else {
                Logger.logError(LOG_TAG, "SSH connection info not provided in intent");
                showError(getString(R.string.error_not_connected));
                return false;
            }
        } else {
            // Build connection info from individual components
            if (user == null || user.isEmpty()) {
                user = "root"; // Default user
            }
            if (host == null || host.isEmpty()) {
                Logger.logError(LOG_TAG, "Host not provided in intent");
                showError(getString(R.string.error_not_connected));
                return false;
            }
            mConnectionInfo = new SSHConnectionInfo(user, host, port, socketPath);
        }

        // Get initial path
        mCurrentPath = intent.getStringExtra(EXTRA_INITIAL_PATH);
        if (mCurrentPath == null || mCurrentPath.isEmpty()) {
            mCurrentPath = DEFAULT_INITIAL_PATH;
        }

        Logger.logDebug(LOG_TAG, "Parsed intent: " + mConnectionInfo.toString() + ", path: " + mCurrentPath);
        return true;
    }

    /**
     * Set up click listeners for navigation and actions.
     */
    private void setupClickListeners() {
        // Back button - navigate to parent directory
        mBackButton.setOnClickListener(v -> navigateToParent());

        // Refresh button - reload current directory
        mRefreshButton.setOnClickListener(v -> loadDirectory(mCurrentPath));

        // File item click - enter directory or show file info
        mFileListView.setOnItemClickListener((parent, view, position, id) -> {
            RemoteFile file = mAdapter.getItem(position);
            if (file != null) {
                onFileItemClick(file);
            }
        });
    }

    /**
     * Load directory contents asynchronously.
     *
     * Shows loading indicator while fetching, updates list on success,
     * shows error toast on failure.
     *
     * @param path Remote directory path to load
     */
    private void loadDirectory(@NonNull String path) {
        if (mIsLoading) {
            Logger.logDebug(LOG_TAG, "Already loading, skipping duplicate request");
            return;
        }

        if (!mIsActive) {
            Logger.logDebug(LOG_TAG, "Activity not active, skipping load");
            return;
        }

        mIsLoading = true;
        mCurrentPath = path;

        Logger.logDebug(LOG_TAG, "Loading directory: " + path);

        // Show loading state
        showLoading(true);

        // Run listing in background thread
        new Thread(() -> {
            try {
                List<RemoteFile> files = RemoteFileLister.listDirectory(
                    this, mConnectionInfo, path);

                // Update UI on main thread
                mMainThreadHandler.post(() -> {
                    if (!mIsActive) {
                        Logger.logDebug(LOG_TAG, "Activity no longer active, discarding result");
                        return;
                    }

                    mIsLoading = false;
                    showLoading(false);

                    if (files.isEmpty()) {
                        // Empty directory - show empty view
                        mAdapter.updateFiles(files);
                        mEmptyView.setText(getString(R.string.empty_directory));
                        mEmptyView.setVisibility(View.VISIBLE);
                        mFileListView.setVisibility(View.GONE);
                        Logger.logDebug(LOG_TAG, "Directory is empty: " + path);
                    } else {
                        // Update adapter with new files
                        mAdapter.updateFiles(files);
                        mEmptyView.setVisibility(View.GONE);
                        mFileListView.setVisibility(View.VISIBLE);
                        Logger.logDebug(LOG_TAG, "Loaded " + files.size() + " files from " + path);
                    }

                    // Update breadcrumb navigation
                    updateBreadcrumb(path);
                });

            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Exception loading directory: " + e.getMessage());

                mMainThreadHandler.post(() -> {
                    if (!mIsActive) return;

                    mIsLoading = false;
                    showLoading(false);
                    showError(getString(R.string.error_listing_directory) + ": " + e.getMessage());

                    // Show empty view with error
                    mAdapter.updateFiles(new ArrayList<>());
                    mEmptyView.setText(getString(R.string.error_listing_directory));
                    mEmptyView.setVisibility(View.VISIBLE);
                    mFileListView.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    /**
     * Show or hide loading indicator.
     *
     * @param isLoading true to show loading, false to hide
     */
    private void showLoading(boolean isLoading) {
        if (mLoadingIndicator != null) {
            mLoadingIndicator.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        }
        if (mContentContainer != null && isLoading) {
            // Keep content visible but dimmed/semi-transparent during loading
            // Or hide completely if loading indicator replaces content
            // For now, just show loading overlay
        }
        // Disable interactions during loading
        mFileListView.setEnabled(!isLoading);
        mBackButton.setEnabled(!isLoading && !mPathStack.isEmpty());
        mRefreshButton.setEnabled(!isLoading);
    }

    /**
     * Show error message as Toast.
     *
     * @param message Error message to display
     */
    private void showError(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Handle file item click.
     *
     * If clicked item is a directory, navigate into it.
     * If it's a file or symlink, show info (future: download/open).
     *
     * @param file Clicked RemoteFile item
     */
    private void onFileItemClick(@NonNull RemoteFile file) {
        Logger.logDebug(LOG_TAG, "File clicked: " + file.getName() + " (type: " + file.getType() + ")");

        if (file.isDirectory()) {
            // Navigate into subdirectory
            String newPath = file.getPath();
            mPathStack.push(mCurrentPath);
            loadDirectory(newPath);
        } else {
            // For files, show info toast (future: implement download/open)
            String info = file.getName() + " (" + file.getSizeFormatted() + ")";
            Toast.makeText(this, info, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Navigate to parent directory.
     *
     * If path stack is empty, shows root or stays at current level.
     */
    private void navigateToParent() {
        if (mPathStack.isEmpty()) {
            // Already at root level, can't go back further
            Logger.logDebug(LOG_TAG, "Path stack empty, already at root level");
            Toast.makeText(this, "Already at root directory", Toast.LENGTH_SHORT).show();
            return;
        }

        String parentPath = mPathStack.pop();
        Logger.logDebug(LOG_TAG, "Navigating to parent: " + parentPath);
        loadDirectory(parentPath);
    }

    /**
     * Update breadcrumb navigation display.
     *
     * Creates clickable path segments for each directory level.
     *
     * @param path Current full path
     */
    private void updateBreadcrumb(@NonNull String path) {
        mBreadcrumbPathLayout.removeAllViews();

        // Split path into segments
        String normalizedPath = path;
        if (normalizedPath.startsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(1);
        }
        if (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        String[] segments = normalizedPath.split("/");
        if (segments.length == 0 || (segments.length == 1 && segments[0].isEmpty())) {
            // Root directory
            addBreadcrumbSegment("/", "/", true);
            return;
        }

        // Build breadcrumb: root -> ... -> current
        StringBuilder accumulatedPath = new StringBuilder();

        // Add root segment
        addBreadcrumbSegment("/", "/", segments.length == 0);
        accumulatedPath.append("/");

        // Add each path segment
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].isEmpty()) {
                accumulatedPath.append(segments[i]);
                boolean isLast = (i == segments.length - 1);
                addBreadcrumbSegment(segments[i], accumulatedPath.toString(), isLast);

                if (!isLast) {
                    accumulatedPath.append("/");
                }
            }
        }
    }

    /**
     * Add a breadcrumb segment TextView to the navigation.
     *
     * @param text Display text for segment
     * @param path Full path this segment represents
     * @param isCurrent true if this is the current (last) segment
     */
    private void addBreadcrumbSegment(@NonNull String text, @NonNull String path, boolean isCurrent) {
        TextView segmentView = new TextView(this);
        segmentView.setText(isCurrent ? text : text + "/");
        segmentView.setTextSize(14);

        if (isCurrent) {
            // Current segment: bold style
            segmentView.setTextColor(getColor(com.termux.shared.R.color.white));
            segmentView.setTypeface(segmentView.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            // Clickable segment: link style
            segmentView.setTextColor(getColor(com.termux.shared.R.color.blue_link_dark));
            segmentView.setClickable(true);
            segmentView.setOnClickListener(v -> {
                Logger.logDebug(LOG_TAG, "Breadcrumb clicked: " + path);

                // Calculate how many levels to pop from stack
                // Pop until we reach the clicked path
                while (!mPathStack.isEmpty() && !mPathStack.peek().equals(path)) {
                    mPathStack.pop();
                }

                // Add current path to stack before navigating
                if (!mCurrentPath.equals(path)) {
                    mPathStack.push(mCurrentPath);
                }

                loadDirectory(path);
            });
        }

        segmentView.setPadding(8, 8, 8, 8);
        mBreadcrumbPathLayout.addView(segmentView);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        // Get the selected file from the adapter
        android.widget.AdapterView.AdapterContextMenuInfo info =
            (android.widget.AdapterView.AdapterContextMenuInfo) menuInfo;
        int position = info.position;

        mSelectedFile = mAdapter.getItem(position);
        if (mSelectedFile == null) {
            Logger.logError(LOG_TAG, "Selected file is null at position " + position);
            return;
        }

        // Set context menu header with file name
        menu.setHeaderTitle(mSelectedFile.getName());

        // Inflate the context menu
        getMenuInflater().inflate(R.menu.context_menu_remote_file, menu);

        Logger.logDebug(LOG_TAG, "Context menu created for: " + mSelectedFile.getName());
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (mSelectedFile == null) {
            Logger.logError(LOG_TAG, "No file selected for context menu action");
            return super.onContextItemSelected(item);
        }

        int itemId = item.getItemId();
        Logger.logDebug(LOG_TAG, "Context menu item selected: " + itemId + " for file: " + mSelectedFile.getName());

        if (itemId == R.id.menu_rename) {
            showRenameDialog(mSelectedFile);
            return true;
        } else if (itemId == R.id.menu_delete) {
            showDeleteConfirmationDialog(mSelectedFile);
            return true;
        } else if (itemId == R.id.menu_new_folder) {
            showNewFolderDialog();
            return true;
        }
        // Copy and Move will be implemented in later slices

        return super.onContextItemSelected(item);
    }

    /**
     * Show delete confirmation dialog for a file or directory.
     *
     * @param file File to delete
     */
    private void showDeleteConfirmationDialog(@NonNull RemoteFile file) {
        String title = getString(R.string.title_confirm_delete);
        String message = file.isDirectory()
            ? getString(R.string.message_confirm_delete_directory, file.getName())
            : getString(R.string.message_confirm_delete_file, file.getName());

        MessageDialogUtils.showMessage(
            this,
            title,
            message,
            getString(android.R.string.yes),
            (dialog, which) -> executeDelete(file),
            getString(android.R.string.no),
            null,
            null
        );
    }

    /**
     * Execute delete operation on a file or directory.
     *
     * @param file File to delete
     */
    private void executeDelete(@NonNull RemoteFile file) {
        Logger.logDebug(LOG_TAG, "Executing delete for: " + file.getPath());

        new Thread(() -> {
            RemoteFileOperator.OperationResult result = RemoteFileOperator.delete(
                this,
                mConnectionInfo,
                file.getPath(),
                file.isDirectory(),  // recursive if directory
                true                  // force
            );

            mMainThreadHandler.post(() -> {
                if (result.success) {
                    String message = file.isDirectory()
                        ? getString(R.string.success_directory_deleted)
                        : getString(R.string.success_file_deleted);
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    // Refresh the directory listing
                    loadDirectory(mCurrentPath);
                } else {
                    String error = getString(R.string.error_delete_failed);
                    if (result.errorMessage != null) {
                        error = error + ": " + result.errorMessage;
                    }
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    Logger.logError(LOG_TAG, "Delete failed: " + result);
                }
            });
        }).start();
    }

    /**
     * Show rename dialog for a file or directory.
     *
     * @param file File to rename
     */
    private void showRenameDialog(@NonNull RemoteFile file) {
        // Use same title for both files and directories - TextInputDialogUtils requires resource ID
        TextInputDialogUtils.textInput(
            this,
            R.string.action_rename,
            file.getName(),
            android.R.string.ok,
            newText -> executeRename(file, newText),
            0,  // neutralButtonText (not used)
            null,
            android.R.string.cancel,
            null,
            null
        );
    }

    /**
     * Execute rename operation on a file or directory.
     *
     * @param file File to rename
     * @param newName New name for the file
     */
    private void executeRename(@NonNull RemoteFile file, @NonNull String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newName.equals(file.getName())) {
            Toast.makeText(this, "Name unchanged", Toast.LENGTH_SHORT).show();
            return;
        }

        Logger.logDebug(LOG_TAG, "Executing rename for: " + file.getPath() + " to: " + newName);

        new Thread(() -> {
            RemoteFileOperator.OperationResult result = RemoteFileOperator.rename(
                this,
                mConnectionInfo,
                file.getPath(),
                newName.trim()
            );

            mMainThreadHandler.post(() -> {
                if (result.success) {
                    String message = file.isDirectory()
                        ? getString(R.string.success_directory_renamed)
                        : getString(R.string.success_file_renamed);
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    // Refresh the directory listing
                    loadDirectory(mCurrentPath);
                } else {
                    String error = getString(R.string.error_rename_failed);
                    if (result.errorMessage != null) {
                        error = error + ": " + result.errorMessage;
                    }
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    Logger.logError(LOG_TAG, "Rename failed: " + result);
                }
            });
        }).start();
    }

    /**
     * Show new folder dialog to create a directory in current path.
     */
    private void showNewFolderDialog() {
        TextInputDialogUtils.textInput(
            this,
            R.string.title_new_folder,
            "",
            android.R.string.ok,
            folderName -> executeMkdir(folderName),
            0,  // neutralButtonText (not used)
            null,
            android.R.string.cancel,
            null,
            null
        );
    }

    /**
     * Execute mkdir operation to create a new folder.
     *
     * @param folderName Name of the folder to create
     */
    private void executeMkdir(@NonNull String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) {
            Toast.makeText(this, "Folder name cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        // Compute full path - must be final for lambda
        String basePath = mCurrentPath.endsWith("/") ? mCurrentPath : mCurrentPath + "/";
        final String newPath = basePath + folderName.trim();

        Logger.logDebug(LOG_TAG, "Executing mkdir at: " + newPath);

        new Thread(() -> {
            RemoteFileOperator.OperationResult result = RemoteFileOperator.mkdir(
                this,
                mConnectionInfo,
                newPath,
                false  // createParents - just create single directory
            );

            mMainThreadHandler.post(() -> {
                if (result.success) {
                    Toast.makeText(this, getString(R.string.success_folder_created), Toast.LENGTH_SHORT).show();
                    // Refresh the directory listing
                    loadDirectory(mCurrentPath);
                } else {
                    String error = getString(R.string.error_mkdir_failed);
                    if (result.errorMessage != null) {
                        error = error + ": " + result.errorMessage;
                    }
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    Logger.logError(LOG_TAG, "Mkdir failed: " + result);
                }
            });
        }).start();
    }
}