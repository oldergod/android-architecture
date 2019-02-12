/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.Activity
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import com.example.android.architecture.blueprints.todoapp.R
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskActivity
import com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskFragment
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailIntent.ActivateTaskIntent
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailIntent.CompleteTaskIntent
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailViewState.UiNotification.TASK_ACTIVATED
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailViewState.UiNotification.TASK_COMPLETE
import com.example.android.architecture.blueprints.todoapp.taskdetail.TaskDetailViewState.UiNotification.TASK_DELETED
import com.example.android.architecture.blueprints.todoapp.util.ToDoViewModelFactory
import com.jakewharton.rxbinding2.view.RxView
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import kotlin.LazyThreadSafetyMode.NONE

/**
 * Main UI for the task detail screen.
 */
class TaskDetailFragment : Fragment(), MviView<TaskDetailIntent, TaskDetailViewState> {
  private lateinit var detailTitle: TextView
  private lateinit var detailDescription: TextView
  private lateinit var detailCompleteStatus: CheckBox
  private lateinit var fab: FloatingActionButton

  private val viewModel: TaskDetailViewModel by lazy(NONE) {
    ViewModelProviders
        .of(this, ToDoViewModelFactory.getInstance(context!!))
        .get(TaskDetailViewModel::class.java)
  }

  // Used to manage the data flow lifecycle and avoid memory leak.
  private var disposables = CompositeDisposable()
  private val deleteTaskIntentPublisher = PublishSubject.create<TaskDetailIntent.DeleteTask>()

  private val argumentTaskId: String
    get() = arguments!!.getString(ARGUMENT_TASK_ID)

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
      savedInstanceState: Bundle?): View? {
    val root = inflater.inflate(R.layout.taskdetail_frag, container, false)
    setHasOptionsMenu(true)
    detailTitle = root.findViewById<View>(R.id.task_detail_title) as TextView
    detailDescription = root.findViewById<View>(R.id.task_detail_description) as TextView
    detailCompleteStatus = root.findViewById<View>(R.id.task_detail_complete) as CheckBox

    // Set up floating action button
    fab = activity!!.findViewById<View>(R.id.fab_edit_task) as FloatingActionButton

    return root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    bind()
  }

  /**
   * Connect the [MviView] with the [MviViewModel]
   * We subscribe to the [MviViewModel] before passing it the [MviView]'s [MviIntent]s.
   * If we were to pass [MviIntent]s to the [MviViewModel] before listening to it,
   * emitted [MviViewState]s could be lost
   */
  private fun bind() {
    // Subscribe to the ViewModel and call render for every emitted state
    disposables.add(viewModel.states().subscribe(this::render))
    // Pass the UI's intents to the ViewModel
    viewModel.processIntents(intents())

    // Debounce the FAB clicks to avoid consecutive clicks and navigate to EditTask
    RxView.clicks(fab).debounce(200, TimeUnit.MILLISECONDS)
        .subscribe { showEditTask(argumentTaskId) }
  }

  override fun onDestroy() {
    super.onDestroy()
    disposables.dispose()
  }

  override fun intents(): Observable<TaskDetailIntent> {
    return Observable.merge(initialIntent(), checkBoxIntents(), deleteIntent())
  }

  /**
   * The initial Intent the [MviView] emit to convey to the [MviViewModel]
   * that it is ready to receive data.
   * This initial Intent is also used to pass any parameters the [MviViewModel] might need
   * to render the initial [MviViewState] (e.g. the task id to load).
   */
  private fun initialIntent(): Observable<TaskDetailIntent.InitialIntent> {
    return Observable.just(TaskDetailIntent.InitialIntent(argumentTaskId))
  }

  private fun checkBoxIntents(): Observable<TaskDetailIntent> {
    return RxView.clicks(detailCompleteStatus).map {
      if (detailCompleteStatus.isChecked) {
        CompleteTaskIntent(argumentTaskId)
      } else {
        ActivateTaskIntent(argumentTaskId)
      }
    }

  }

  private fun deleteIntent(): Observable<TaskDetailIntent.DeleteTask> {
    return deleteTaskIntentPublisher
  }

  override fun render(state: TaskDetailViewState) {
    setLoadingIndicator(state.loading)

    if (!state.title.isEmpty()) {
      showTitle(state.title)
    } else {
      hideTitle()
    }

    if (!state.description.isEmpty()) {
      showDescription(state.description)
    } else {
      hideDescription()
    }

    showActive(state.active)

    when (state.uiNotification) {
      TASK_COMPLETE -> showTaskMarkedComplete()
      TASK_ACTIVATED -> showTaskMarkedActive()
      TASK_DELETED -> activity!!.finish()
      null -> {
      }
    }
  }

  override fun onOptionsItemSelected(item: MenuItem?): Boolean {
    when (item!!.itemId) {
      R.id.menu_delete -> {
        deleteTaskIntentPublisher.onNext(TaskDetailIntent.DeleteTask(argumentTaskId))
        return true
      }
    }
    return false
  }

  override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
    inflater!!.inflate(R.menu.taskdetail_fragment_menu, menu)
    super.onCreateOptionsMenu(menu, inflater)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_EDIT_TASK) {
      // If the task was edited successfully, go back to the list.
      if (resultCode == Activity.RESULT_OK) {
        activity!!.finish()
        return
      }
    }
    super.onActivityResult(requestCode, resultCode, data)
  }

  fun setLoadingIndicator(active: Boolean) {
    if (active) {
      detailTitle.text = ""
      detailDescription.text = getString(R.string.loading)
    }
  }

  fun hideDescription() {
    detailDescription.visibility = View.GONE
  }

  fun hideTitle() {
    detailTitle.visibility = View.GONE
  }

  fun showActive(isActive: Boolean) {
    detailCompleteStatus.isChecked = !isActive
  }

  fun showDescription(description: String) {
    detailDescription.visibility = View.VISIBLE
    detailDescription.text = description
  }

  private fun showEditTask(taskId: String) {
    val intent = Intent(context, AddEditTaskActivity::class.java)
    intent.putExtra(AddEditTaskFragment.ARGUMENT_EDIT_TASK_ID, taskId)
    startActivityForResult(intent, REQUEST_EDIT_TASK)
  }

  fun showTaskMarkedComplete() {
    Snackbar.make(view!!, getString(R.string.task_marked_complete), Snackbar.LENGTH_LONG)
        .show()
  }

  fun showTaskMarkedActive() {
    Snackbar.make(view!!, getString(R.string.task_marked_active), Snackbar.LENGTH_LONG)
        .show()
  }

  fun showTitle(title: String) {
    detailTitle.visibility = View.VISIBLE
    detailTitle.text = title
  }

  companion object {
    private const val ARGUMENT_TASK_ID = "TASK_ID"
    private const val REQUEST_EDIT_TASK = 1

    operator fun invoke(taskId: String): TaskDetailFragment {
      return TaskDetailFragment().apply {
        arguments = Bundle().apply {
          putString(ARGUMENT_TASK_ID, taskId)
        }
      }
    }
  }
}
