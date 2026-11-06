package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDraftView;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;

/** Editing a process never touches the engine. Only publishing deploys. */
public interface ProcessDraftService {

    ProcessDraftView create(CreateProcessDraftRequest request);

    ProcessDraftView get(String processDefinitionKey);

    ProcessDraftView save(String processDefinitionKey, SaveProcessDraftRequest request);

    ProcessDraftView loadVersion(String processDefinitionKey, DraftVersionRequest request);

    ProcessDraftView discard(String processDefinitionKey, DraftRevisionRequest request);
}
