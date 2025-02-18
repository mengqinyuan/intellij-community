// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.add.v2

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.and
import com.intellij.openapi.observable.util.notEqualsTo
import com.intellij.openapi.observable.util.or
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.validation.WHEN_PROPERTY_CHANGED
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.ui.showingScope
import com.jetbrains.python.PyBundle.message
import com.jetbrains.python.newProject.collector.InterpreterStatisticsInfo
import com.jetbrains.python.sdk.ModuleOrProject
import com.jetbrains.python.sdk.VirtualEnvReader
import com.jetbrains.python.sdk.add.PySdkCreator
import com.jetbrains.python.sdk.add.v2.PythonInterpreterSelectionMode.*
import com.jetbrains.python.statistics.InterpreterCreationMode
import com.jetbrains.python.statistics.InterpreterTarget
import com.jetbrains.python.statistics.InterpreterType
import com.jetbrains.python.util.ErrorSink
import com.jetbrains.python.util.ShowingMessageErrorSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path


/**
 * If `onlyAllowedInterpreterTypes` then only these types are displayed. All types displayed otherwise
 */
class PythonAddNewEnvironmentPanel(val projectPath: StateFlow<Path>, onlyAllowedInterpreterTypes: Set<PythonInterpreterSelectionMode>? = null, private val errorSink: ErrorSink) : PySdkCreator {
  private val propertyGraph = PropertyGraph()
  private val allowedInterpreterTypes = (onlyAllowedInterpreterTypes ?: PythonInterpreterSelectionMode.entries).also {
    assert(it.isNotEmpty()) {
      "When provided, onlyAllowedInterpreterTypes shouldn't be empty"
    }
  }

  private val initMutex = Mutex()

  private var selectedMode = propertyGraph.property(this.allowedInterpreterTypes.first())
  private var _projectVenv = propertyGraph.booleanProperty(selectedMode, PROJECT_VENV)
  private var _baseConda = propertyGraph.booleanProperty(selectedMode, BASE_CONDA)
  private var _custom = propertyGraph.booleanProperty(selectedMode, CUSTOM)
  private var venvHint = propertyGraph.property("")

  private lateinit var pythonBaseVersionComboBox: PythonInterpreterComboBox
  private var initialized = false

  private fun updateVenvLocationHint() {
    val get = selectedMode.get()
    if (get == PROJECT_VENV) venvHint.set(message("sdk.create.simple.venv.hint", projectPath.value.resolve(VirtualEnvReader.DEFAULT_VIRTUALENV_DIRNAME).toString()))
    else if (get == BASE_CONDA && PROJECT_VENV in allowedInterpreterTypes) venvHint.set(message("sdk.create.simple.conda.hint"))
  }

  private lateinit var custom: PythonAddCustomInterpreter
  private lateinit var model: PythonMutableTargetAddInterpreterModel

  fun buildPanel(outerPanel: Panel) {
    //presenter = PythonAddInterpreterPresenter(state, uiContext = Dispatchers.EDT + ModalityState.current().asContextElement())
    model = PythonLocalAddInterpreterModel(PyInterpreterModelParams(service<PythonAddSdkService>().coroutineScope,
                                                                    Dispatchers.EDT + ModalityState.current().asContextElement(), projectPath))
    model.navigator.selectionMode = selectedMode
    //presenter.controller = model

    custom = PythonAddCustomInterpreter(model, projectPath = projectPath, errorSink = ShowingMessageErrorSync)

    val validationRequestor = WHEN_PROPERTY_CHANGED(selectedMode)



    with(outerPanel) {
      if (allowedInterpreterTypes.size > 1) { // No need to show control with only one selection
        row(message("sdk.create.interpreter.type")) {
          segmentedButton(allowedInterpreterTypes) { text = message(it.nameKey) }
            .bind(selectedMode)
        }.topGap(TopGap.MEDIUM)
      }

      row(message("sdk.create.python.version")) {
        pythonBaseVersionComboBox = pythonInterpreterComboBox(model.state.baseInterpreter,
                                                              model,
                                                              model::addInterpreter,
                                                              model.interpreterLoading)
          .align(AlignX.FILL)
          .component
      }.visibleIf(_projectVenv)

      rowsRange {
        executableSelector(model.state.condaExecutable,
                           validationRequestor,
                           message("sdk.create.custom.venv.executable.path", "conda"),
                           message("sdk.create.custom.venv.missing.text", "conda"),
                           createInstallCondaFix(model, errorSink))
        //.displayLoaderWhen(presenter.detectingCondaExecutable, scope = presenter.scope, uiContext = presenter.uiContext)
      }.visibleIf(_baseConda)

      row("") {
        comment("").bindText(venvHint).apply {
          component.showingScope("Update hint") {
            projectPath.collect {
              updateVenvLocationHint()
            }
          }
        }
      }.visibleIf(_projectVenv or (_baseConda and model.state.condaExecutable.notEqualsTo(UNKNOWN_EXECUTABLE)))

      rowsRange {
        custom.buildPanel(this, validationRequestor)
      }.visibleIf(_custom)
    }
    selectedMode.afterChange { updateVenvLocationHint() }
  }


  fun onShown() {
    val modalityState = ModalityState.current().asContextElement()
    model.scope.launch(Dispatchers.EDT + modalityState) {
      initMutex.withLock {
        if (!initialized) {
          model.initialize()
          pythonBaseVersionComboBox.setItems(model.baseInterpreters)
          custom.onShown()
          updateVenvLocationHint()
          model.navigator.restoreLastState(allowedInterpreterTypes)
          initialized = true
        }
      }
    }
  }

  @Deprecated("Use one with module or project")
  fun getSdk(): Sdk {
    val moduleOrProject = ModuleOrProject.ProjectOnly(ProjectManager.getInstance().defaultProject)
    return if (ApplicationManager.getApplication().isDispatchThread) {
      runWithModalProgressBlocking(ModalTaskOwner.guess(), "...") {
        getSdk(moduleOrProject)
      }
    }
    else {
      runBlockingCancellable { getSdk(moduleOrProject) }
    }.getOrThrow()
  }

  override suspend fun getSdk(moduleOrProject: ModuleOrProject): Result<Sdk> {
    model.navigator.saveLastState()
    return when (selectedMode.get()) {
      PROJECT_VENV -> {
        val projectPath = projectPath.value
        // todo just keep venv path, all the rest is in the model
        model.setupVirtualenv(projectPath.resolve(VirtualEnvReader.DEFAULT_VIRTUALENV_DIRNAME), projectPath)
      }
      BASE_CONDA -> model.selectCondaEnvironment(base = true)
      CUSTOM -> custom.currentSdkManager.getOrCreateSdk(moduleOrProject)
    }
  }


  override fun createStatisticsInfo(): InterpreterStatisticsInfo = when (selectedMode.get()) {
    PROJECT_VENV -> InterpreterStatisticsInfo(InterpreterType.VIRTUALENV,
                                              InterpreterTarget.LOCAL,
                                              false,
                                              false,
                                              false,
      //presenter.projectLocationContext is WslContext,
                                              false,
                                              InterpreterCreationMode.SIMPLE)
    BASE_CONDA -> InterpreterStatisticsInfo(InterpreterType.BASE_CONDA,
                                            InterpreterTarget.LOCAL,
                                            false,
                                            false,
                                            true,
      //presenter.projectLocationContext is WslContext,
                                            false,
                                            InterpreterCreationMode.SIMPLE)
    CUSTOM -> custom.createStatisticsInfo()
  }
}