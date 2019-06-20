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

package com.example.android.architecture.blueprints.todoapp.statistics

import android.arch.lifecycle.ViewModel
import com.example.android.architecture.blueprints.todoapp.mvibase.MviAction
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent
import com.example.android.architecture.blueprints.todoapp.mvibase.MviResult
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState
import com.example.android.architecture.blueprints.todoapp.statistics.StatisticsAction.LoadStatisticsAction
import com.example.android.architecture.blueprints.todoapp.statistics.StatisticsResult.LoadStatisticsResult
import com.example.android.architecture.blueprints.todoapp.statistics.StatisticsResult.LoadStatisticsResult.Failure
import com.example.android.architecture.blueprints.todoapp.statistics.StatisticsResult.LoadStatisticsResult.InFlight
import com.example.android.architecture.blueprints.todoapp.statistics.StatisticsResult.LoadStatisticsResult.Success
import com.example.android.architecture.blueprints.todoapp.util.notOfType
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.BiFunction
import io.reactivex.subjects.PublishSubject

/**
 * Listens to user actions from the UI ([StatisticsFragment]), retrieves the data and updates
 * the UI as required.
 *
 * @property actionProcessorHolder Contains and executes the business logic of all emitted actions.
 */
class StatisticsViewModel(
    private val actionProcessorHolder: StatisticsActionProcessorHolder
) : ViewModel(), MviViewModel<StatisticsIntent, StatisticsViewState> {

  /**
   * Proxy subject used to keep the stream alive even after the UI gets recycled.
   * This is basically used to keep ongoing events and the last cached State alive
   * while the UI disconnects and reconnects on config changes.
   */
  private val intentsSubject: PublishSubject<StatisticsIntent> = PublishSubject.create()
  private val statesObservable: Observable<StatisticsViewState> = compose()
  private val disposables = CompositeDisposable()

  /**
   * take only the first ever InitialIntent and all intents of other types
   * to avoid reloading data on config changes
   */
  private val intentFilter: ObservableTransformer<StatisticsIntent, StatisticsIntent>
    get() = ObservableTransformer { intents ->
      intents.publish { shared ->
        Observable.merge<StatisticsIntent>(
            shared.ofType(StatisticsIntent.InitialIntent::class.java).take(1),
            shared.notOfType(StatisticsIntent.InitialIntent::class.java)
        )
      }
    }

  override fun processIntents(intents: Observable<StatisticsIntent>) {
    disposables.add(intents.subscribe(intentsSubject::onNext))
  }

  override fun states(): Observable<StatisticsViewState> = statesObservable

  /**
   * Compose all components to create the stream logic
   */
  private fun compose(): Observable<StatisticsViewState> {
    return intentsSubject
        .compose<StatisticsIntent>(intentFilter)
        .map<StatisticsAction>(this::actionFromIntent)
        .compose(actionProcessorHolder.actionProcessor)
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
        .autoConnect(0)
  }

  /**
   * Translate an [MviIntent] to an [MviAction].
   * Used to decouple the UI and the business logic to allow easy testings and reusability.
   */
  private fun actionFromIntent(intent: StatisticsIntent): StatisticsAction {
    return when (intent) {
      is StatisticsIntent.InitialIntent -> LoadStatisticsAction
    }
  }

  override fun onCleared() {
    disposables.dispose()
  }

  companion object {
    /**
     * The Reducer is where [MviViewState], that the [MviView] will use to
     * render itself, are created.
     * It takes the last cached [MviViewState], the latest [MviResult] and
     * creates a new [MviViewState] by only updating the related fields.
     * This is basically like a big switch statement of all possible types for the [MviResult]
     */
    private val reducer = BiFunction { previousState: StatisticsViewState, result: StatisticsResult ->
      when (result) {
        is LoadStatisticsResult -> when (result) {
          is Success ->
            previousState.copy(
                isLoading = false,
                activeCount = result.activeCount,
                completedCount = result.completedCount
            )
          is Failure -> previousState.copy(isLoading = false, error = result.error)
          is InFlight -> previousState.copy(isLoading = true)
        }
      }
    }
  }
}
