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

package com.example.android.architecture.blueprints.todoapp.addedittask;

import android.app.Activity;
import android.arch.lifecycle.ViewModelProviders;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.android.architecture.blueprints.todoapp.R;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState;
import com.example.android.architecture.blueprints.todoapp.util.ToDoViewModelFactory;
import com.jakewharton.rxbinding2.view.RxView;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;

/**
 * Main UI for the add task screen. Users can enter a task title and description.
 */
public class AddEditTaskFragment extends Fragment implements MviView<AddEditTaskIntent, AddEditTaskViewState> {
    public static final String ARGUMENT_EDIT_TASK_ID = "EDIT_TASK_ID";
    private TextView mTitle;
    private TextView mDescription;
    private FloatingActionButton fab;
    private AddEditTaskViewModel mViewModel;
    // Used to manage the data flow lifecycle and avoid memory leak.
    private CompositeDisposable mDisposables = new CompositeDisposable();

    public static AddEditTaskFragment newInstance() {
        return new AddEditTaskFragment();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDisposables.dispose();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.addtask_frag, container, false);
        mTitle = (TextView) root.findViewById(R.id.add_task_title);
        mDescription = (TextView) root.findViewById(R.id.add_task_description);
        setHasOptionsMenu(true);
        return root;
    }


    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fab = (FloatingActionButton) getActivity().findViewById(R.id.fab_edit_task_done);
        fab.setImageResource(R.drawable.ic_done);

        mViewModel = ViewModelProviders.of(this, ToDoViewModelFactory.getInstance(getContext()))
                .get(AddEditTaskViewModel.class);
        mDisposables = new CompositeDisposable();
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
    }

    @Override
    public Observable<AddEditTaskIntent> intents() {
        return Observable.merge(initialIntent(), saveTaskIntent());
    }

    /**
     * The initial Intent the {@link MviView} emit to convey to the {@link MviViewModel}
     * that it is ready to receive data.
     * This initial Intent is also used to pass any parameters the {@link MviViewModel} might need
     * to render the initial {@link MviViewState} (e.g. the task id to load).
     */
    private Observable<AddEditTaskIntent.InitialIntent> initialIntent() {
        return Observable.just(AddEditTaskIntent.InitialIntent.create(getArgumentTaskId()));
    }

    private Observable<AddEditTaskIntent.SaveTask> saveTaskIntent() {
        // Wrap the FAB click events into a SaveTaskIntent and set required information
        return RxView.clicks(fab).map(ignored ->
                AddEditTaskIntent.SaveTask.create(
                        getArgumentTaskId(),
                        mTitle.getText().toString(),
                        mDescription.getText().toString()));
    }

    @Override
    public void render(AddEditTaskViewState state) {
        if (state.isSaved()) {
            showTasksList();
            return;
        }
        if (state.isEmpty()) {
            showEmptyTaskError();
        }
        if (!state.title().isEmpty()) {
            setTitle(state.title());
        }
        if (!state.description().isEmpty()) {
            setDescription(state.description());
        }
    }

    private void showEmptyTaskError() {
        Snackbar.make(mTitle, getString(R.string.empty_task_message), Snackbar.LENGTH_LONG).show();
    }

    private void showTasksList() {
        getActivity().setResult(Activity.RESULT_OK);
        getActivity().finish();
    }

    private void setTitle(String title) {
        mTitle.setText(title);
    }

    private void setDescription(String description) {
        mDescription.setText(description);
    }

    @Nullable
    private String getArgumentTaskId() {
        Bundle args = getArguments();
        if (args == null) return null;
        return args.getString(ARGUMENT_EDIT_TASK_ID);
    }
}
