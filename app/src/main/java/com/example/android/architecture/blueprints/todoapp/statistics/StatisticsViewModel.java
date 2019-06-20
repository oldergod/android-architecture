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

package com.example.android.architecture.blueprints.todoapp.statistics;

import android.arch.lifecycle.ViewModel;
import android.support.annotation.NonNull;

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
 * Listens to user actions from the UI ({@link StatisticsFragment}), retrieves the data and updates
 * the UI as required.
 */
public class StatisticsViewModel extends ViewModel
        implements MviViewModel<StatisticsIntent, StatisticsViewState> {

    /**
     * Proxy subject used to keep the stream alive even after the UI gets recycled.
     * This is basically used to keep ongoing events and the last cached State alive
     * while the UI disconnects and reconnects on config changes.
     */
    @NonNull
    private PublishSubject<StatisticsIntent> mIntentsSubject;
    @NonNull
    private Observable<StatisticsViewState> mStatesObservable;
    @NonNull
    private CompositeDisposable mDisposables = new CompositeDisposable();
    /**
     * Contains and executes the business logic of all emitted actions.
     */
    @NonNull
    private StatisticsActionProcessorHolder mActionProcessorHolder;

    public StatisticsViewModel(@NonNull StatisticsActionProcessorHolder actionProcessorHolder) {
        this.mActionProcessorHolder = checkNotNull(actionProcessorHolder, "actionProcessorHolder cannot be null");

        mIntentsSubject = PublishSubject.create();
        mStatesObservable = compose();
    }

    @Override
    public void processIntents(Observable<StatisticsIntent> intents) {
        mDisposables.add(intents.subscribe(mIntentsSubject::onNext));
    }

    @Override
    public Observable<StatisticsViewState> states() {
        return mStatesObservable;
    }

    /**
     * Compose all components to create the stream logic
     */
    private Observable<StatisticsViewState> compose() {
        return mIntentsSubject
                .compose(intentFilter)
                .map(this::actionFromIntent)
                .compose(mActionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(StatisticsViewState.idle(), reducer)
                // When a reducer just emits previousState, there's no reason to call render. In fact,
                // redrawing the UI in cases like this can cause jank (e.g. messing up snackbar animations
                // by showing the same snackbar twice in rapid succession).
                .distinctUntilChanged()
                // Emit the last one event of the stream on subscription.
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
    private ObservableTransformer<StatisticsIntent, StatisticsIntent> intentFilter =
            intents -> intents.publish(shared ->
                    Observable.merge(
                            shared.ofType(StatisticsIntent.InitialIntent.class).take(1),
                            shared.filter(intent -> !(intent instanceof StatisticsIntent.InitialIntent))
                    )
            );

    /**
     * Translate an {@link MviIntent} to an {@link MviAction}.
     * Used to decouple the UI and the business logic to allow easy testings and reusability.
     */
    private StatisticsAction actionFromIntent(MviIntent intent) {
        if (intent instanceof StatisticsIntent.InitialIntent) {
            return StatisticsAction.LoadStatistics.create();
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
    private static BiFunction<StatisticsViewState, StatisticsResult, StatisticsViewState> reducer =
            (previousState, result) -> {
                StatisticsViewState.Builder stateBuilder = previousState.buildWith();
                if (result instanceof StatisticsResult.LoadStatistics) {
                    StatisticsResult.LoadStatistics loadResult = (StatisticsResult.LoadStatistics) result;
                    switch (loadResult.status()) {
                        case SUCCESS:
                            return stateBuilder.isLoading(false)
                                    .activeCount(loadResult.activeCount())
                                    .completedCount(loadResult.completedCount())
                                    .build();
                        case FAILURE:
                            return stateBuilder.isLoading(false).error(loadResult.error()).build();
                        case IN_FLIGHT:
                            return stateBuilder.isLoading(true).build();
                    }
                } else {
                    throw new IllegalArgumentException("Don't know this result " + result);
                }
                // Fail for unhandled results
                throw new IllegalStateException("Mishandled result? Should not happen (as always)");
            };
}
