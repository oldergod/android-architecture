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

import android.arch.lifecycle.ViewModel;
import android.support.annotation.NonNull;

import com.example.android.architecture.blueprints.todoapp.data.Task;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviAction;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviResult;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.subjects.PublishSubject;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Listens to user actions from the UI ({@link AddEditTaskFragment}), retrieves the data and updates
 * the UI as required.
 */
public class AddEditTaskViewModel extends ViewModel
        implements MviViewModel<AddEditTaskIntent, AddEditTaskViewState> {

    /**
     * Proxy subject used to keep the stream alive even after the UI gets recycled.
     * This is basically used to keep ongoing events and the last cached State alive
     * while the UI disconnects and reconnects on config changes.
     */
    @NonNull
    private PublishSubject<AddEditTaskIntent> mIntentsSubject;
    @NonNull
    private Observable<AddEditTaskViewState> mStatesObservable;
    @NonNull
    private CompositeDisposable mDisposables = new CompositeDisposable();
    /**
     * Contains and executes the business logic of all emitted actions.
     */
    @NonNull
    private AddEditTaskActionProcessorHolder mActionProcessorHolder;

    public AddEditTaskViewModel(@NonNull AddEditTaskActionProcessorHolder actionProcessorHolder) {
        mActionProcessorHolder = checkNotNull(actionProcessorHolder);

        mIntentsSubject = PublishSubject.create();
        mStatesObservable = compose();
    }

    @Override
    public void processIntents(Observable<AddEditTaskIntent> intents) {
        mDisposables.add(intents.subscribe(mIntentsSubject::onNext));
    }

    @Override
    public Observable<AddEditTaskViewState> states() {
        return mStatesObservable;
    }

    /**
     * Compose all components to create the stream logic
     */
    private Observable<AddEditTaskViewState> compose() {
        return mIntentsSubject
                .compose(intentFilter)
                .map(this::actionFromIntent)
                // Special case where we do not want to pass this event down the stream
                .filter(action -> !(action instanceof AddEditTaskAction.SkipMe))
                .compose(mActionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(AddEditTaskViewState.idle(), reducer)
                // When a reducer just emits previousState, there's no reason to call render. In fact,
                // redrawing the UI in cases like this can cause jank (e.g. messing up snackbar animations
                // by showing the same snackbar twice in rapid succession).
                .distinctUntilChanged()
                // Emit the last one event of the stream on subscription
                // Useful when a View rebinds to the ViewModel after rotation.
                .replay(1)
                // Create the stream on creation without waiting for anyone to subscribe
                // This allows the stream to stay alive even when the UI disconnects and
                // match the stream's lifecycle to the ViewModel's one.
                .autoConnect(0);
    }

    /**
     * take only the first ever InitialIntent and all intents of other types
     * to avoid reloading data on config changes
     */
    private ObservableTransformer<AddEditTaskIntent, AddEditTaskIntent> intentFilter =
            intents -> intents.publish(shared ->
                    Observable.merge(
                            shared.ofType(AddEditTaskIntent.InitialIntent.class).take(1),
                            shared.filter(intent -> !(intent instanceof AddEditTaskIntent.InitialIntent))
                    )
            );

    /**
     * Translate an {@link MviIntent} to an {@link MviAction}.
     * Used to decouple the UI and the business logic to allow easy testings and reusability.
     */
    private AddEditTaskAction actionFromIntent(MviIntent intent) {
        if (intent instanceof AddEditTaskIntent.InitialIntent) {
            String taskId = ((AddEditTaskIntent.InitialIntent) intent).taskId();
            if (taskId == null) {
                // new Task, so nothing to do
                return AddEditTaskAction.SkipMe.create();
            } else {
                return AddEditTaskAction.PopulateTask.create(taskId);
            }
        }
        if (intent instanceof AddEditTaskIntent.SaveTask) {
            AddEditTaskIntent.SaveTask saveTaskIntent = (AddEditTaskIntent.SaveTask) intent;
            final String taskId = saveTaskIntent.taskId();
            if (taskId == null) {
                return AddEditTaskAction.CreateTask.create(
                        saveTaskIntent.title(), saveTaskIntent.description());
            } else {
                return AddEditTaskAction.UpdateTask.create(
                        taskId, saveTaskIntent.title(), saveTaskIntent.description());
            }
        }
        // Fail for unhandled intents
        throw new IllegalArgumentException("do not know how to treat this intent " + intent);
    }

    @Override
    protected void onCleared() {
        mDisposables.dispose();
    }

    /**
     * The Reducer is where {@link MviViewState}, that the {@link MviView} will use to
     * render itself, are created.
     * It takes the last cached {@link MviViewState}, the latest {@link MviResult} and
     * creates a new {@link MviViewState} by only updating the related fields.
     * This is basically like a big switch statement of all possible types for the {@link MviResult}
     */
    private static BiFunction<AddEditTaskViewState, AddEditTaskResult, AddEditTaskViewState> reducer =
            (previousState, result) -> {
                AddEditTaskViewState.Builder stateBuilder = previousState.buildWith();
                if (result instanceof AddEditTaskResult.PopulateTask) {
                    AddEditTaskResult.PopulateTask populateTaskResult =
                            (AddEditTaskResult.PopulateTask) result;
                    switch (populateTaskResult.status()) {
                        case SUCCESS:
                            Task task = checkNotNull(populateTaskResult.task());
                            if (task.isActive()) {
                                stateBuilder.title(task.getTitle());
                                stateBuilder.description(task.getDescription());
                            }
                            return stateBuilder.build();
                        case FAILURE:
                            Throwable error = checkNotNull(populateTaskResult.error());
                            return stateBuilder.error(error).build();
                        case IN_FLIGHT:
                            // nothing to do
                            return stateBuilder.build();
                    }
                }
                if (result instanceof AddEditTaskResult.CreateTask) {
                    AddEditTaskResult.CreateTask createTaskResult =
                            (AddEditTaskResult.CreateTask) result;
                    if (createTaskResult.isEmpty()) {
                        return stateBuilder.isEmpty(true).build();
                    } else {
                        return stateBuilder.isEmpty(false).isSaved(true).build();
                    }
                }
                if (result instanceof AddEditTaskResult.UpdateTask) {
                    return stateBuilder.isSaved(true).build();
                }
                // Fail for unhandled results
                throw new IllegalStateException("Mishandled result? Should not happen―as always: " + result);
            };
}
