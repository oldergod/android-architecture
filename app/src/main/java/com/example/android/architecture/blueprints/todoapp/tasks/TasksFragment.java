/*
 * Copyright 2016, The Android Open Source Project
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

package com.example.android.architecture.blueprints.todoapp.tasks;

import android.app.Activity;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.LifecycleRegistryOwner;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.PopupMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.example.android.architecture.blueprints.todoapp.R;
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskActivity;
import com.example.android.architecture.blueprints.todoapp.data.Task;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState;
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailActivity;
import com.example.android.architecture.blueprints.todoapp.util.ToDoViewModelFactory;
import com.jakewharton.rxbinding2.support.v4.widget.RxSwipeRefreshLayout;

import java.util.ArrayList;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.PublishSubject;

import static com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ACTIVE_TASKS;
import static com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ALL_TASKS;
import static com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.COMPLETED_TASKS;

/**
 * Display a grid of {@link Task}s. User can choose to view all, active or completed tasks.
 */
public class TasksFragment extends Fragment
        implements LifecycleRegistryOwner, MviView<TasksIntent, TasksViewState> {
    private LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);

    private TasksViewModel mViewModel;
    private TasksAdapter mListAdapter;
    private View mNoTasksView;
    private ImageView mNoTaskIcon;
    private TextView mNoTaskMainView;
    private TextView mNoTaskAddView;
    private LinearLayout mTasksView;
    private TextView mFilteringLabelView;
    private ScrollChildSwipeRefreshLayout mSwipeRefreshLayout;
    private PublishSubject<TasksIntent.RefreshIntent> mRefreshIntentPublisher =
            PublishSubject.create();
    private PublishSubject<TasksIntent.ClearCompletedTasksIntent> mClearCompletedTaskIntentPublisher =
            PublishSubject.create();
    private PublishSubject<TasksIntent.ChangeFilterIntent> mChangeFilterIntentPublisher =
            PublishSubject.create();
    // Used to manage the data flow lifecycle and avoid memory leak.
    private CompositeDisposable mDisposables = new CompositeDisposable();

    public static TasksFragment newInstance() {
        return new TasksFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mListAdapter = new TasksAdapter(new ArrayList<>(0));
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mViewModel = ViewModelProviders.of(this, ToDoViewModelFactory.getInstance(getContext()))
                .get(TasksViewModel.class);
    }

    @Override
    public void onStart() {
        super.onStart();

        bind();
    }

    /**
     * Connect the {@link MviView} with the {@link MviViewModel}
     * We subscribe to the {@link MviViewModel} before passing it the {@link MviView}'s {@link MviIntent}s.
     * If we were to pass {@link MviIntent}s to the {@link MviViewModel} before listening to it,
     * emitted {@link MviViewState}s could be lost
     */
    private void bind() {
        // Subscribe to the ViewModel and call render for every emitted state
        mDisposables.add(mViewModel.states().subscribe(this::render));
        // Pass the UI's intents to the ViewModel
        mViewModel.processIntents(intents());

        mDisposables.add(
                mListAdapter.getTaskClickObservable().subscribe(task -> showTaskDetailsUi(task.getId())));
    }

    @Override
    public void onResume() {
        super.onResume();
        // conflicting with the initial intent but needed when coming back from the
        // AddEditTask activity to refresh the list.
        mRefreshIntentPublisher.onNext(TasksIntent.RefreshIntent.create(false));
    }

    @Override
    public void onStop() {
        super.onStop();
        mDisposables.clear();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // If a task was successfully added, show snackbar
        if (AddEditTaskActivity.REQUEST_ADD_TASK == requestCode && Activity.RESULT_OK == resultCode) {
            showSuccessfullySavedMessage();
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.tasks_frag, container, false);

        // Set up tasks view
        ListView listView = (ListView) root.findViewById(R.id.tasks_list);
        listView.setAdapter(mListAdapter);
        mFilteringLabelView = (TextView) root.findViewById(R.id.filteringLabel);
        mTasksView = (LinearLayout) root.findViewById(R.id.tasksLL);

        // Set up  no tasks view
        mNoTasksView = root.findViewById(R.id.noTasks);
        mNoTaskIcon = (ImageView) root.findViewById(R.id.noTasksIcon);
        mNoTaskMainView = (TextView) root.findViewById(R.id.noTasksMain);
        mNoTaskAddView = (TextView) root.findViewById(R.id.noTasksAdd);
        mNoTaskAddView.setOnClickListener(ignored -> showAddTask());

        // Set up floating action button
        FloatingActionButton fab = (FloatingActionButton) getActivity().findViewById(R.id.fab_add_task);

        fab.setImageResource(R.drawable.ic_add);
        fab.setOnClickListener(ignored -> showAddTask());

        // Set up progress indicator
        mSwipeRefreshLayout = (ScrollChildSwipeRefreshLayout) root.findViewById(R.id.refresh_layout);
        mSwipeRefreshLayout.setColorSchemeColors(
                ContextCompat.getColor(getActivity(), R.color.colorPrimary),
                ContextCompat.getColor(getActivity(), R.color.colorAccent),
                ContextCompat.getColor(getActivity(), R.color.colorPrimaryDark));
        // Set the scrolling view in the custom SwipeRefreshLayout.
        mSwipeRefreshLayout.setScrollUpChild(listView);

        setHasOptionsMenu(true);

        return root;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_clear:
                mClearCompletedTaskIntentPublisher.onNext(TasksIntent.ClearCompletedTasksIntent.create());
                break;
            case R.id.menu_filter:
                showFilteringPopUpMenu();
                break;
            case R.id.menu_refresh:
                mRefreshIntentPublisher.onNext(TasksIntent.RefreshIntent.create(true));
                break;
        }
        return true;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.tasks_fragment_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public Observable<TasksIntent> intents() {
        return Observable.merge(initialIntent(), refreshIntent(), adapterIntents(),
                clearCompletedTaskIntent()).mergeWith(changeFilterIntent());
    }

    @Override
    public void render(TasksViewState state) {
        mSwipeRefreshLayout.setRefreshing(state.isLoading());
        if (state.error() != null) {
            showLoadingTasksError();
            return;
        }

        if (state.taskActivated()) showMessage(getString(R.string.task_marked_active));

        if (state.taskComplete()) showMessage(getString(R.string.task_marked_complete));

        if (state.completedTasksCleared()) showMessage(getString(R.string.completed_tasks_cleared));

        if (state.tasks().isEmpty()) {
            switch (state.tasksFilterType()) {
                case ACTIVE_TASKS:
                    showNoActiveTasks();
                    break;
                case COMPLETED_TASKS:
                    showNoCompletedTasks();
                    break;
                default:
                    showNoTasks();
                    break;
            }
        } else {
            mListAdapter.replaceData(state.tasks());

            mTasksView.setVisibility(View.VISIBLE);
            mNoTasksView.setVisibility(View.GONE);

            switch (state.tasksFilterType()) {
                case ACTIVE_TASKS:
                    showActiveFilterLabel();
                    break;
                case COMPLETED_TASKS:
                    showCompletedFilterLabel();
                    break;
                default:
                    showAllFilterLabel();
                    break;
            }
        }
    }

    @Override
    public LifecycleRegistry getLifecycle() {
        return mLifecycleRegistry;
    }

    private void showFilteringPopUpMenu() {
        PopupMenu popup = new PopupMenu(getContext(), getActivity().findViewById(R.id.menu_filter));
        popup.getMenuInflater().inflate(R.menu.filter_tasks, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.active:
                    mChangeFilterIntentPublisher.onNext(TasksIntent.ChangeFilterIntent.create(ACTIVE_TASKS));
                    break;
                case R.id.completed:
                    mChangeFilterIntentPublisher.onNext(
                            TasksIntent.ChangeFilterIntent.create(COMPLETED_TASKS));
                    break;
                default:
                    mChangeFilterIntentPublisher.onNext(TasksIntent.ChangeFilterIntent.create(ALL_TASKS));
                    break;
            }
            return true;
        });

        popup.show();
    }

    private void showMessage(String message) {
        View view = getView();
        if (view == null) return;
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
    }

    /**
     * The initial Intent the {@link MviView} emit to convey to the {@link MviViewModel}
     * that it is ready to receive data.
     * This initial Intent is also used to pass any parameters the {@link MviViewModel} might need
     * to render the initial {@link MviViewState} (e.g. the task id to load).
     */
    private Observable<TasksIntent.InitialIntent> initialIntent() {
        return Observable.just(TasksIntent.InitialIntent.create());
    }

    private Observable<TasksIntent.RefreshIntent> refreshIntent() {
        return RxSwipeRefreshLayout.refreshes(mSwipeRefreshLayout)
                .map(ignored -> TasksIntent.RefreshIntent.create(false))
                .mergeWith(mRefreshIntentPublisher);
    }

    private Observable<TasksIntent.ClearCompletedTasksIntent> clearCompletedTaskIntent() {
        return mClearCompletedTaskIntentPublisher;
    }

    private Observable<TasksIntent.ChangeFilterIntent> changeFilterIntent() {
        return mChangeFilterIntentPublisher;
    }

    private Observable<TasksIntent> adapterIntents() {
        return mListAdapter.getTaskToggleObservable().map(task -> {
            if (!task.isCompleted()) {
                return TasksIntent.CompleteTaskIntent.create(task);
            } else {
                return TasksIntent.ActivateTaskIntent.create(task);
            }
        });
    }

    private void showNoActiveTasks() {
        showNoTasksViews(getResources().getString(R.string.no_tasks_active),
                R.drawable.ic_check_circle_24dp, false);
    }

    private void showNoTasks() {
        showNoTasksViews(getResources().getString(R.string.no_tasks_all),
                R.drawable.ic_assignment_turned_in_24dp, true);
    }

    private void showNoCompletedTasks() {
        showNoTasksViews(getResources().getString(R.string.no_tasks_completed),
                R.drawable.ic_verified_user_24dp, false);
    }

    private void showSuccessfullySavedMessage() {
        showMessage(getString(R.string.successfully_saved_task_message));
    }

    private void showNoTasksViews(String mainText, int iconRes, boolean showAddView) {
        mTasksView.setVisibility(View.GONE);
        mNoTasksView.setVisibility(View.VISIBLE);

        mNoTaskMainView.setText(mainText);
        mNoTaskIcon.setImageDrawable(getResources().getDrawable(iconRes));
        mNoTaskAddView.setVisibility(showAddView ? View.VISIBLE : View.GONE);
    }

    private void showActiveFilterLabel() {
        mFilteringLabelView.setText(getResources().getString(R.string.label_active));
    }

    private void showCompletedFilterLabel() {
        mFilteringLabelView.setText(getResources().getString(R.string.label_completed));
    }

    private void showAllFilterLabel() {
        mFilteringLabelView.setText(getResources().getString(R.string.label_all));
    }

    private void showAddTask() {
        Intent intent = new Intent(getContext(), AddEditTaskActivity.class);
        startActivityForResult(intent, AddEditTaskActivity.REQUEST_ADD_TASK);
    }

    private void showTaskDetailsUi(String taskId) {
        // in it's own Activity, since it makes more sense that way and it gives us the flexibility
        // to show some MviIntent stubbing.
        Intent intent = new Intent(getContext(), TaskDetailActivity.class);
        intent.putExtra(TaskDetailActivity.EXTRA_TASK_ID, taskId);
        startActivity(intent);
    }

    private void showLoadingTasksError() {
        showMessage(getString(R.string.loading_tasks_error));
    }
}
