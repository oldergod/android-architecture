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

package com.example.android.architecture.blueprints.todoapp.taskdetail;

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

import static com.example.android.architecture.blueprints.todoapp.util.UiNotificationStatus.SHOW;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Listens to user actions from the UI ({@link TaskDetailFragment}), retrieves the data and updates
 * the UI as required.
 */
public class TaskDetailViewModel extends ViewModel
        implements MviViewModel<TaskDetailIntent, TaskDetailViewState> {

    /**
     * Proxy subject used to keep the stream alive even after the UI gets recycled.
     * This is basically used to keep ongoing events and the last cached State alive
     * while the UI disconnects and reconnects on config changes.
     */
    @NonNull
    private PublishSubject<TaskDetailIntent> mIntentsSubject;
    @NonNull
    private Observable<TaskDetailViewState> mStatesObservable;
    @NonNull
    private CompositeDisposable mDisposables = new CompositeDisposable();
    /**
     * Contains and executes the business logic of all emitted actions.
     */
    @NonNull
    private TaskDetailActionProcessorHolder mActionProcessorHolder;

    public TaskDetailViewModel(@NonNull TaskDetailActionProcessorHolder actionProcessorHolder) {
        mActionProcessorHolder = checkNotNull(actionProcessorHolder);

        mIntentsSubject = PublishSubject.create();
        mStatesObservable = compose();
    }

    @Override
    public void processIntents(Observable<TaskDetailIntent> intents) {
        mDisposables.add(intents.subscribe(mIntentsSubject::onNext));
    }

    @Override
    public Observable<TaskDetailViewState> states() {
        return mStatesObservable;
    }

    /**
     * Compose all components to create the stream logic
     */
    private Observable<TaskDetailViewState> compose() {
        return mIntentsSubject
                .compose(intentFilter)
                .map(this::actionFromIntent)
                .compose(mActionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(TaskDetailViewState.idle(), reducer)
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
    private ObservableTransformer<TaskDetailIntent, TaskDetailIntent> intentFilter =
            intents -> intents.publish(shared ->
                    Observable.merge(
                            shared.ofType(TaskDetailIntent.InitialIntent.class).take(1),
                            shared.filter(intent -> !(intent instanceof TaskDetailIntent.InitialIntent))
                    )
            );

    /**
     * Translate an {@link MviIntent} to an {@link MviAction}.
     * Used to decouple the UI and the business logic to allow easy testings and reusability.
     */
    private TaskDetailAction actionFromIntent(MviIntent intent) {
        if (intent instanceof TaskDetailIntent.InitialIntent) {
            String taskId = ((TaskDetailIntent.InitialIntent) intent).taskId();
            checkNotNull(taskId);
            return TaskDetailAction.PopulateTask.create(taskId);
        }
        if (intent instanceof TaskDetailIntent.DeleteTask) {
            TaskDetailIntent.DeleteTask deleteTaskIntent = (TaskDetailIntent.DeleteTask) intent;
            final String taskId = deleteTaskIntent.taskId();
            return TaskDetailAction.DeleteTask.create(taskId);
        }

        if (intent instanceof TaskDetailIntent.CompleteTaskIntent) {
            TaskDetailIntent.CompleteTaskIntent completeTaskIntent = (TaskDetailIntent.CompleteTaskIntent) intent;
            final String taskId = completeTaskIntent.taskId();
            return TaskDetailAction.CompleteTask.create(taskId);
        }

        if (intent instanceof TaskDetailIntent.ActivateTaskIntent) {
            TaskDetailIntent.ActivateTaskIntent activateTaskIntent = (TaskDetailIntent.ActivateTaskIntent) intent;
            final String taskId = activateTaskIntent.taskId();
            return TaskDetailAction.ActivateTask.create(taskId);
        }
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
    private static BiFunction<TaskDetailViewState, TaskDetailResult, TaskDetailViewState> reducer =
            (previousState, result) -> {
                TaskDetailViewState.Builder stateBuilder = previousState.buildWith();
                if (result instanceof TaskDetailResult.PopulateTask) {
                    TaskDetailResult.PopulateTask populateTaskResult =
                            (TaskDetailResult.PopulateTask) result;
                    switch (populateTaskResult.status()) {
                        case SUCCESS:
                            Task task = checkNotNull(populateTaskResult.task());
                            stateBuilder.title(task.getTitle());
                            stateBuilder.description(task.getDescription());
                            stateBuilder.active(task.isActive());
                            stateBuilder.loading(false);
                            return stateBuilder.build();
                        case FAILURE:
                            Throwable error = checkNotNull(populateTaskResult.error());
                            stateBuilder.loading(false);
                            return stateBuilder.error(error).build();
                        case IN_FLIGHT:
                            stateBuilder.loading(true);
                            return stateBuilder.build();
                    }
                }
                if (result instanceof TaskDetailResult.DeleteTaskResult) {
                    TaskDetailResult.DeleteTaskResult deleteTaskResult =
                            (TaskDetailResult.DeleteTaskResult) result;
                    switch (deleteTaskResult.status()) {
                        case SUCCESS:
                            return stateBuilder.taskDeleted(true).build();
                        case FAILURE:
                            return stateBuilder.error(deleteTaskResult.error()).build();
                        case IN_FLIGHT:
                            return stateBuilder.build();
                    }
                } else if (result instanceof TaskDetailResult.ActivateTaskResult) {
                    TaskDetailResult.ActivateTaskResult activateTaskResult =
                            (TaskDetailResult.ActivateTaskResult) result;
                    switch (activateTaskResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .taskActivated(activateTaskResult.uiNotificationStatus() == SHOW)
                                    .active(true)
                                    .build();

                        case FAILURE:
                            return stateBuilder.error(activateTaskResult.error()).build();
                        case IN_FLIGHT:
                            return stateBuilder.build();
                    }
                } else if (result instanceof TaskDetailResult.CompleteTaskResult) {
                    TaskDetailResult.CompleteTaskResult completeTaskResult =
                            (TaskDetailResult.CompleteTaskResult) result;
                    switch (completeTaskResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .taskComplete(completeTaskResult.uiNotificationStatus() == SHOW)
                                    .active(false)
                                    .build();

                        case FAILURE:
                            return stateBuilder.error(completeTaskResult.error()).build();
                        case IN_FLIGHT:
                            return stateBuilder.build();
                    }
                }
                // Fail for unhandled results
                throw new IllegalStateException("Mishandled result? Should not happen―as always: " + result);
            };
}
