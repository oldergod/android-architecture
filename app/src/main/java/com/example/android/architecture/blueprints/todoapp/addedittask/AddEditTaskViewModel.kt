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

package com.example.android.architecture.blueprints.todoapp.addedittask

import android.arch.lifecycle.ViewModel
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskAction.CreateTaskAction
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskAction.PopulateTaskAction
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskAction.SkipMe
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskAction.UpdateTaskAction
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskResult.CreateTaskResult
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskResult.PopulateTaskResult
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskResult.UpdateTaskResult
import com.example.android.architecture.blueprints.todoapp.mvibase.MviAction
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent
import com.example.android.architecture.blueprints.todoapp.mvibase.MviResult
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState
import com.example.android.architecture.blueprints.todoapp.util.notOfType
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.BiFunction
import io.reactivex.subjects.PublishSubject

/**
 * Listens to user actions from the UI ([AddEditTaskFragment]), retrieves the data and updates
 * the UI as required.
 *
 * @property actionProcessorHolder Contains and executes the business logic of all emitted actions.
 */
class AddEditTaskViewModel(
    private val actionProcessorHolder: AddEditTaskActionProcessorHolder
) : ViewModel(), MviViewModel<AddEditTaskIntent, AddEditTaskViewState> {

  /**
   * Proxy subject used to keep the stream alive even after the UI gets recycled.
   * This is basically used to keep ongoing events and the last cached State alive
   * while the UI disconnects and reconnects on config changes.
   */
  private val intentsSubject: PublishSubject<AddEditTaskIntent> = PublishSubject.create()
  private val statesObservable: Observable<AddEditTaskViewState> = compose()
  private val disposables = CompositeDisposable()

  /**
   * take only the first ever InitialIntent and all intents of other types
   * to avoid reloading data on config changes
   */
  private val intentFilter: ObservableTransformer<AddEditTaskIntent, AddEditTaskIntent>
    get() = ObservableTransformer { intents ->
      intents.publish { shared ->
        Observable.merge<AddEditTaskIntent>(
            shared.ofType(AddEditTaskIntent.InitialIntent::class.java).take(1),
            shared.notOfType(AddEditTaskIntent.InitialIntent::class.java)
        )
      }
    }

  override fun processIntents(intents: Observable<AddEditTaskIntent>) {
    disposables.add(intents.subscribe(intentsSubject::onNext))
  }

  override fun states(): Observable<AddEditTaskViewState> = statesObservable

  /**
   * Compose all components to create the stream logic
   */
  private fun compose(): Observable<AddEditTaskViewState> {
    return intentsSubject
        .compose<AddEditTaskIntent>(intentFilter)
        .map<AddEditTaskAction>(this::actionFromIntent)
        // Special case where we do not want to pass this event down the stream
        .filter { action -> action !is AddEditTaskAction.SkipMe }
        .compose(actionProcessorHolder.actionProcessor)
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
        .autoConnect(0)
  }

  /**
   * Translate an [MviIntent] to an [MviAction].
   * Used to decouple the UI and the business logic to allow easy testings and reusability.
   */
  private fun actionFromIntent(intent: AddEditTaskIntent): AddEditTaskAction {
    return when (intent) {
      is AddEditTaskIntent.InitialIntent -> {
        if (intent.taskId == null) {
          SkipMe
        } else {
          PopulateTaskAction(taskId = intent.taskId)
        }
      }
      is AddEditTaskIntent.SaveTask -> {
        val (taskId, title, description) = intent
        if (taskId == null) {
          CreateTaskAction(title, description)
        } else {
          UpdateTaskAction(taskId, title, description)
        }
      }
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
    private val reducer = BiFunction { previousState: AddEditTaskViewState, result: AddEditTaskResult ->
      when (result) {
        is PopulateTaskResult -> when (result) {
          is PopulateTaskResult.Success -> {
            result.task.let { task ->
              if (task.active) {
                previousState.copy(title = task.title!!, description = task.description!!)
              } else {
                previousState
              }
            }
          }
          is PopulateTaskResult.Failure -> previousState.copy(error = result.error)
          is PopulateTaskResult.InFlight -> previousState
        }
        is CreateTaskResult -> when (result) {
          is CreateTaskResult.Success -> previousState.copy(isEmpty = false, isSaved = true)
          is CreateTaskResult.Empty -> previousState.copy(isEmpty = true)
        }
        is UpdateTaskResult -> previousState.copy(isSaved = true)
      }
    }
  }
}
