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

package com.example.android.architecture.blueprints.todoapp.taskdetail

import android.arch.lifecycle.ViewModel
import com.example.android.architecture.blueprints.todoapp.mvibase.MviAction
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent
import com.example.android.architecture.blueprints.todoapp.mvibase.MviResult
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailAction.ActivateTaskAction
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailAction.CompleteTaskAction
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailAction.DeleteTaskAction
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailAction.PopulateTaskAction
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailResult.ActivateTaskResult
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailResult.CompleteTaskResult
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailResult.DeleteTaskResult
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailResult.PopulateTaskResult
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailViewState.UiNotification.TASK_ACTIVATED
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailViewState.UiNotification.TASK_COMPLETE
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailViewState.UiNotification.TASK_DELETED
import com.example.android.architecture.blueprints.todoapp.util.notOfType
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.BiFunction
import io.reactivex.subjects.PublishSubject

/**
 * Listens to user actions from the UI ([TaskDetailFragment]), retrieves the data and updates
 * the UI as required.
 *
 * @property actionProcessorHolder Contains and executes the business logic of all emitted actions.
 */
class TaskDetailViewModel(
  private val actionProcessorHolder: TaskDetailActionProcessorHolder
) : ViewModel(), MviViewModel<TaskDetailIntent, TaskDetailViewState> {

  /**
   * Proxy subject used to keep the stream alive even after the UI gets recycled.
   * This is basically used to keep ongoing events and the last cached State alive
   * while the UI disconnects and reconnects on config changes.
   */
  private val intentsSubject: PublishSubject<TaskDetailIntent> = PublishSubject.create()
  private val statesObservable: Observable<TaskDetailViewState> = compose()
  private val disposables = CompositeDisposable()

  /**
   * take only the first ever InitialIntent and all intents of other types
   * to avoid reloading data on config changes
   */
  private val intentFilter: ObservableTransformer<TaskDetailIntent, TaskDetailIntent>
    get() = ObservableTransformer { intents ->
      intents.publish { shared ->
        Observable.merge<TaskDetailIntent>(
            shared.ofType(TaskDetailIntent.InitialIntent::class.java).take(1),
            shared.notOfType(TaskDetailIntent.InitialIntent::class.java)
        )
      }
    }

  override fun processIntents(intents: Observable<TaskDetailIntent>) {
    disposables.add(intents.subscribe(intentsSubject::onNext))
  }

  override fun states(): Observable<TaskDetailViewState> = statesObservable

  /**
   * Compose all components to create the stream logic
   */
  private fun compose(): Observable<TaskDetailViewState> {
    return intentsSubject
        .compose<TaskDetailIntent>(intentFilter)
        .map(this::actionFromIntent)
        .compose(actionProcessorHolder.actionProcessor)
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
        .autoConnect(0)
  }

  /**
   * Translate an [MviIntent] to an [MviAction].
   * Used to decouple the UI and the business logic to allow easy testings and reusability.
   */
  private fun actionFromIntent(intent: TaskDetailIntent): TaskDetailAction {
    return when (intent) {
      is TaskDetailIntent.InitialIntent -> PopulateTaskAction(intent.taskId)
      is TaskDetailIntent.DeleteTask -> DeleteTaskAction(intent.taskId)
      is TaskDetailIntent.ActivateTaskIntent -> ActivateTaskAction(intent.taskId)
      is TaskDetailIntent.CompleteTaskIntent -> CompleteTaskAction(intent.taskId)
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
    private val reducer =
      BiFunction { previousState: TaskDetailViewState, result: TaskDetailResult ->
        when (result) {
          is PopulateTaskResult -> when (result) {
            is PopulateTaskResult.Success -> previousState.copy(
                loading = false,
                title = result.task.title!!,
                description = result.task.description!!,
                active = result.task.active
            )
            is PopulateTaskResult.Failure -> previousState.copy(
                loading = false, error = result.error
            )
            is PopulateTaskResult.InFlight -> previousState.copy(loading = true)
          }
          is ActivateTaskResult -> when (result) {
            is ActivateTaskResult.Success -> previousState.copy(
                uiNotification = TASK_ACTIVATED,
                active = true
            )
            is ActivateTaskResult.Failure -> previousState.copy(error = result.error)
            is ActivateTaskResult.InFlight -> previousState
            is ActivateTaskResult.HideUiNotification ->
              if (previousState.uiNotification == TASK_ACTIVATED) {
                previousState.copy(
                    uiNotification = null
                )
              } else {
                previousState
              }
          }
          is CompleteTaskResult -> when (result) {
            is CompleteTaskResult.Success -> previousState.copy(
                uiNotification = TASK_COMPLETE,
                active = false
            )
            is CompleteTaskResult.Failure -> previousState.copy(error = result.error)
            is CompleteTaskResult.InFlight -> previousState
            is CompleteTaskResult.HideUiNotification ->
              if (previousState.uiNotification == TASK_COMPLETE) {
                previousState.copy(
                    uiNotification = null
                )
              } else {
                previousState
              }
          }
          is DeleteTaskResult -> when (result) {
            is DeleteTaskResult.Success -> previousState.copy(uiNotification = TASK_DELETED)
            is DeleteTaskResult.Failure -> previousState.copy(error = result.error)
            is DeleteTaskResult.InFlight -> previousState
          }
        }
      }
  }
}
