package com.example.android.architecture.blueprints.todoapp.mvibase

import io.reactivex.Observable
import io.reactivex.Observer

/**
 * Object that will subscribes to a [MviView]'s [MviIntent]s,
 * process it and emit a [MviViewState] back.
 *
 * @param I Top class of the [MviIntent] that the [MviViewModel] will be subscribing
 * to.
 * @param S Top class of the [MviViewState] the [MviViewModel] will be emitting.
 */
interface MviViewModel<I : MviIntent, S : MviViewState> {
  val intentsObserver: Observer<I>

  fun states(): Observable<S>
}
