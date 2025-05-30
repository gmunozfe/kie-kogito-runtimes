/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kie.kogito.process.impl;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jbpm.process.instance.InternalProcessRuntime;
import org.jbpm.ruleflow.core.RuleFlowProcess;
import org.jbpm.workflow.core.Node;
import org.jbpm.workflow.core.WorkflowProcess;
import org.jbpm.workflow.instance.NodeInstance;
import org.jbpm.workflow.instance.WorkflowProcessInstance;
import org.jbpm.workflow.instance.impl.NodeInstanceImpl;
import org.jbpm.workflow.instance.impl.WorkflowProcessInstanceImpl;
import org.jbpm.workflow.instance.node.WorkItemNodeInstance;
import org.kie.api.runtime.process.ProcessRuntime;
import org.kie.internal.process.CorrelationAwareProcessRuntime;
import org.kie.internal.process.CorrelationKey;
import org.kie.internal.process.CorrelationProperty;
import org.kie.kogito.Model;
import org.kie.kogito.correlation.CompositeCorrelation;
import org.kie.kogito.correlation.Correlation;
import org.kie.kogito.correlation.CorrelationInstance;
import org.kie.kogito.internal.process.event.KogitoEventListener;
import org.kie.kogito.internal.process.runtime.KogitoNodeInstance;
import org.kie.kogito.internal.process.runtime.KogitoProcessInstance;
import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.Policy;
import org.kie.kogito.internal.process.workitem.WorkItemNotFoundException;
import org.kie.kogito.internal.process.workitem.WorkItemTransition;
import org.kie.kogito.jobs.TimerDescription;
import org.kie.kogito.process.EventDescription;
import org.kie.kogito.process.MutableProcessInstances;
import org.kie.kogito.process.NodeInstanceNotFoundException;
import org.kie.kogito.process.NodeNotFoundException;
import org.kie.kogito.process.Process;
import org.kie.kogito.process.ProcessError;
import org.kie.kogito.process.ProcessInstance;
import org.kie.kogito.process.ProcessInstanceNotFoundException;
import org.kie.kogito.process.Signal;
import org.kie.kogito.process.WorkItem;
import org.kie.kogito.process.flexible.AdHocFragment;
import org.kie.kogito.process.flexible.Milestone;
import org.kie.kogito.process.impl.lock.ProcessInstanceAtomicLockStrategy;
import org.kie.kogito.process.impl.lock.ProcessInstanceLockStrategy;
import org.kie.kogito.process.workitems.InternalKogitoWorkItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractProcessInstance<T extends Model> implements ProcessInstance<T> {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractProcessInstance.class);

    private static final String KOGITO_PROCESS_INSTANCE = "KogitoProcessInstance";

    protected final T variables;
    protected final AbstractProcess<T> process;
    protected InternalProcessRuntime rt;
    protected WorkflowProcessInstance processInstance;

    protected Integer status;

    private final AtomicBoolean removed;

    protected String id;
    protected CorrelationKey correlationKey;
    protected String description;

    protected ProcessError processError;

    protected Consumer<AbstractProcessInstance<?>> reloadSupplier;

    protected CompletionEventListener completionEventListener;

    protected long version;

    private Optional<CorrelationInstance> correlationInstance = Optional.empty();

    private ProcessInstanceLockStrategy processInstanceLockStrategy;

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, ProcessRuntime rt) {
        this(process, variables, null, rt);
    }

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, String businessKey, ProcessRuntime rt) {
        this(process, variables, businessKey, rt, null);
    }

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, String businessKey, ProcessRuntime rt, CompositeCorrelation correlation) {
        this.process = process;
        this.rt = (InternalProcessRuntime) rt;
        this.variables = variables;
        this.removed = new AtomicBoolean(false);
        this.processInstanceLockStrategy = ProcessInstanceAtomicLockStrategy.instance();
        setCorrelationKey(businessKey);

        Map<String, Object> map = bind(variables);

        org.kie.api.definition.process.Process processDefinition = process.get();
        if (processDefinition instanceof WorkflowProcess) {
            ((WorkflowProcess) processDefinition).getInputValidator().ifPresent(v -> v.validate(map));
        }
        String processId = processDefinition.getId();
        syncProcessInstance((WorkflowProcessInstance) ((CorrelationAwareProcessRuntime) rt).createProcessInstance(processId, correlationKey, map));
        processInstance.setMetaData(KOGITO_PROCESS_INSTANCE, this);

        if (Objects.nonNull(correlation)) {
            this.correlationInstance = Optional.of(process.correlations().create(correlation, id()));
        }
    }

    /**
     * Without providing a ProcessRuntime the ProcessInstance can only be used as read-only
     * 
     * @param process
     * @param variables
     * @param wpi
     */
    public AbstractProcessInstance(AbstractProcess<T> process, T variables, org.kie.api.runtime.process.WorkflowProcessInstance wpi) {
        this.process = process;
        this.variables = variables;
        syncProcessInstance((WorkflowProcessInstance) wpi);
        unbind(variables, processInstance.getVariables());
        this.removed = new AtomicBoolean(false);
        this.processInstanceLockStrategy = ProcessInstanceAtomicLockStrategy.instance();
    }

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, ProcessRuntime rt, org.kie.api.runtime.process.WorkflowProcessInstance wpi) {
        this.process = process;
        this.rt = (InternalProcessRuntime) rt;
        this.variables = variables;
        syncProcessInstance((WorkflowProcessInstance) wpi);
        reconnect();
        this.removed = new AtomicBoolean(false);
        this.processInstanceLockStrategy = ProcessInstanceAtomicLockStrategy.instance();
    }

    protected void reconnect() {
        LOG.debug("reconnect process {}", processInstance.getId());
        //set correlation
        if (correlationInstance.isEmpty()) {
            correlationInstance = process().correlations().findByCorrelatedId(id());
        }

        if (processInstance.getKnowledgeRuntime() == null) {
            processInstance.setKnowledgeRuntime(getProcessRuntime().getInternalKieRuntime());
        }
        getProcessRuntime().getProcessInstanceManager().setLock(((MutableProcessInstances<T>) process.instances()).lock());
        processInstance.reconnect();
        processInstance.setMetaData(KOGITO_PROCESS_INSTANCE, this);
        addCompletionEventListener();

        unbind(variables, processInstance.getVariables());
    }

    private void addCompletionEventListener() {
        if (completionEventListener == null) {
            completionEventListener = new CompletionEventListener();
            processInstance.addEventListener("processInstanceCompleted:" + id, completionEventListener, false);
        }
    }

    private void removeCompletionListener() {
        if (completionEventListener != null) {
            processInstance.removeEventListener("processInstanceCompleted:" + id, completionEventListener, false);
            completionEventListener = null;
        }
    }

    protected void disconnect() {

        if (processInstance == null) {
            return;
        }

        LOG.debug("disconnect process {}", processInstance.getId());

        processInstance.disconnect();
        processInstance.setMetaData(KOGITO_PROCESS_INSTANCE, null);
    }

    private void syncProcessInstance(WorkflowProcessInstance wpi) {
        internalSetProcessInstance(wpi);
        status = wpi.getState();
        id = wpi.getStringId();
        description = wpi.getDescription();
        setCorrelationKey(wpi.getCorrelationKey());
    }

    private void setCorrelationKey(String businessKey) {
        if (businessKey != null && !businessKey.trim().isEmpty()) {
            correlationKey = new StringCorrelationKey(businessKey);
        }
    }

    @Override
    public Optional<Correlation<?>> correlation() {
        return correlationInstance.map(CorrelationInstance::getCorrelation);
    }

    public WorkflowProcessInstance internalGetProcessInstance() {
        return processInstance;
    }

    public void internalSetProcessInstance(WorkflowProcessInstance processInstance) {
        this.processInstance = processInstance;
        processInstance.wrap(this);
    }

    public void internalSetReloadSupplier(Consumer<AbstractProcessInstance<?>> reloadSupplier) {
        this.reloadSupplier = reloadSupplier;
    }

    public void internalRemoveProcessInstance() {
        if (processInstance == null) {
            return;
        }
        status = processInstance.getState();
        if (status == STATE_ERROR) {
            processError = buildProcessError();
        }
        removeCompletionListener();
        if (processInstance.getKnowledgeRuntime() != null) {
            disconnect();
        }

        processInstance = null;
    }

    public boolean hasHeader(String headerName) {
        return processInstance().getHeaders().containsKey(headerName);
    }

    @Override
    public void start() {
        start(Collections.emptyMap());
    }

    @Override
    public void start(Map<String, List<String>> headers) {
        start(null, null, headers);
    }

    @Override
    public void start(String trigger, String referenceId) {
        start(trigger, referenceId, Collections.emptyMap());
    }

    @Override
    public void start(String trigger, String referenceId, Map<String, List<String>> headers) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            if (this.status != KogitoProcessInstance.STATE_PENDING) {
                throw new IllegalStateException("Impossible to start process instance that already has started");
            }
            this.status = KogitoProcessInstance.STATE_ACTIVE;

            if (referenceId != null) {
                processInstance.setReferenceId(referenceId);
            }

            if (headers != null) {
                this.processInstance.setHeaders(headers);
            }

            getProcessRuntime().getProcessInstanceManager().setLock(((MutableProcessInstances<T>) process.instances()).lock());
            getProcessRuntime().getProcessInstanceManager().addProcessInstance(this.processInstance);
            this.id = processInstance.getStringId();
            addCompletionEventListener();
            ((MutableProcessInstances<T>) process.instances()).create(id, this);
            KogitoProcessInstance kogitoProcessInstance = getProcessRuntime().getKogitoProcessRuntime().startProcessInstance(this.id, trigger);
            if (kogitoProcessInstance.getState() != STATE_ABORTED && kogitoProcessInstance.getState() != STATE_COMPLETED) {
                ((MutableProcessInstances<T>) process.instances()).update(this.id(), this);
            }
            unbind(variables, kogitoProcessInstance.getVariables());
            if (this.processInstance != null) {
                this.status = this.processInstance.getState();
            }
            return null;
        });
    }

    @Override
    public void abort() {
        processInstanceLockStrategy.executeOperation(id, () -> {
            String pid = processInstance().getStringId();
            getProcessRuntime().getKogitoProcessRuntime().abortProcessInstance(pid);
            removeOnFinish();
            return null;
        });
    }

    private InternalProcessRuntime getProcessRuntime() {
        if (rt == null) {
            throw new UnsupportedOperationException("Process instance is not connected to a Process Runtime");
        } else {
            return rt;
        }
    }

    @Override
    public <S> void send(Signal<S> signal) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            if (signal.referenceId() != null) {
                processInstance().setReferenceId(signal.referenceId());
            }
            processInstance().signalEvent(signal.channel(), signal.payload());
            removeOnFinish();
            return null;
        });
    }

    @Override
    public Process<T> process() {
        return process;
    }

    @Override
    public T variables() {
        return variables;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public String businessKey() {
        return this.correlationKey == null ? null : this.correlationKey.getName();
    }

    @Override
    public String description() {
        return this.description;
    }

    @Override
    public Date startDate() {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().getStartDate();
        });
    }

    @Override
    public long version() {
        return this.version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    @Override
    public T updateVariables(T updates) {
        Map<String, Object> map = bind(updates);
        variables.update(map);
        return updateVariables(map);
    }

    @Override
    public T updateVariablesPartially(T updates) {
        return updateVariables(this.variables.updatePartially(bind(updates)));
    }

    private T updateVariables(Map<String, Object> map) {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            for (Entry<String, Object> entry : map.entrySet()) {
                processInstance().setVariable(entry.getKey(), entry.getValue());
            }
            ((MutableProcessInstances<T>) process.instances()).update(this.id(), this);
            return variables;
        });
    }

    @Override
    public Optional<ProcessError> error() {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            if (this.status == STATE_ERROR) {
                return Optional.of(this.processError != null ? this.processError : buildProcessError());
            }
            return Optional.empty();
        });
    }

    @Override
    public void startFrom(String nodeId) {
        startFrom(nodeId, Collections.emptyMap());
    }

    @Override
    public void startFrom(String nodeId, Map<String, List<String>> headers) {
        startFrom(nodeId, null, headers);
    }

    @Override
    public void startFrom(String nodeId, String referenceId) {
        startFrom(nodeId, referenceId, Collections.emptyMap());
    }

    @Override
    public void startFrom(String nodeId, String referenceId, Map<String, List<String>> headers) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            processInstance.setStartDate(new Date());
            processInstance.setState(STATE_ACTIVE);
            getProcessRuntime().getProcessInstanceManager().addProcessInstance(this.processInstance);

            this.id = processInstance.getStringId();

            addCompletionEventListener();
            if (referenceId != null) {
                processInstance.setReferenceId(referenceId);
            }
            if (headers != null) {
                this.processInstance.setHeaders(headers);
            }

            internalTriggerNode(nodeId);

            unbind(variables, processInstance.getVariables());
            if (processInstance() != null) {
                this.status = processInstance.getState();
            }
            ((MutableProcessInstances<T>) process.instances()).create(id, this);
            return null;
        });
    }

    @Override
    public void triggerNode(String nodeId) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            internalTriggerNode(nodeId);
            ((MutableProcessInstances<T>) process.instances()).update(id, this);
            return null;
        });
    }

    private void internalTriggerNode(String nodeId) {
        WorkflowProcessInstance wfpi = processInstance();
        RuleFlowProcess rfp = ((RuleFlowProcess) wfpi.getProcess());

        // we avoid create containers incorrectly
        NodeInstance nodeInstance = wfpi.getNodeByPredicate(rfp,
                ni -> Objects.equals(nodeId, ni.getName()) || Objects.equals(nodeId, ni.getId().toExternalFormat()));
        if (nodeInstance == null) {
            throw new NodeNotFoundException(this.id, nodeId);
        }
        nodeInstance.trigger(null, Node.CONNECTION_DEFAULT_TYPE);
    }

    @Override
    public void cancelNodeInstance(String nodeInstanceId) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            NodeInstance nodeInstance = processInstance()
                    .getNodeInstances(true)
                    .stream()
                    .filter(ni -> ni.getStringId().equals(nodeInstanceId))
                    .findFirst()
                    .orElseThrow(() -> new NodeInstanceNotFoundException(this.id, nodeInstanceId));

            nodeInstance.cancel();
            removeOnFinish();
            return null;
        });
    }

    @Override
    public void retriggerNodeInstance(String nodeInstanceId) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            NodeInstance nodeInstance = processInstance()
                    .getNodeInstances(true)
                    .stream()
                    .filter(ni -> ni.getStringId().equals(nodeInstanceId))
                    .findFirst()
                    .orElseThrow(() -> new NodeInstanceNotFoundException(this.id, nodeInstanceId));
            ((NodeInstanceImpl) nodeInstance).retrigger(true);
            removeOnFinish();
            return null;
        });
    }

    protected WorkflowProcessInstance processInstance() {
        if (this.processInstance == null) {
            reloadSupplier.accept(this);
            if (this.processInstance == null) {
                throw new ProcessInstanceNotFoundException(id);
            } else if (getProcessRuntime() != null) {
                reconnect();
            }
        }
        return this.processInstance;
    }

    @Override
    public Collection<KogitoNodeInstance> findNodes(Predicate<KogitoNodeInstance> predicate) {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().getKogitoNodeInstances(predicate, true);
        });
    }

    @Override
    public WorkItem workItem(String workItemId, Policy... policies) {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().getNodeInstances(true).stream()
                    .filter(WorkItemNodeInstance.class::isInstance)
                    .map(WorkItemNodeInstance.class::cast)
                    .filter(w -> enforceException(w.getWorkItem(), policies))
                    .filter(ni -> ni.getWorkItemId().equals(workItemId))
                    .map(this::toBaseWorkItem)
                    .findAny()
                    .orElseThrow(() -> new WorkItemNotFoundException("Work item with id " + workItemId + " was not found in process instance " + id(), workItemId));
        });
    }

    private boolean enforceException(KogitoWorkItem kogitoWorkItem, Policy... policies) {
        Stream.of(policies).forEach(p -> p.enforce(kogitoWorkItem));
        return true;
    }

    @Override
    public List<WorkItem> workItems(Policy... policies) {
        return workItems(WorkItemNodeInstance.class::isInstance, policies);
    }

    @Override
    public List<WorkItem> workItems(Predicate<KogitoNodeInstance> p, Policy... policies) {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().getNodeInstances(true).stream()
                    .filter(p::test)
                    .filter(WorkItemNodeInstance.class::isInstance)
                    .map(WorkItemNodeInstance.class::cast)
                    .filter(w -> enforce(w.getWorkItem(), policies))
                    .map(this::toBaseWorkItem)
                    .toList();
        });
    }

    private WorkItem toBaseWorkItem(WorkItemNodeInstance workItemNodeInstance) {
        InternalKogitoWorkItem workItem = workItemNodeInstance.getWorkItem();
        return new BaseWorkItem(
                workItemNodeInstance.getStringId(),
                workItemNodeInstance.getWorkItemId(),
                workItemNodeInstance.getNode().getId(),
                (String) workItem.getParameters().getOrDefault("TaskName", workItemNodeInstance.getNodeName()),
                workItem.getName(),
                workItem.getState(),
                workItem.getPhaseId(),
                workItem.getPhaseStatus(),
                workItem.getParameters(),
                workItem.getResults(),
                workItem.getExternalReferenceId());
    }

    private boolean enforce(KogitoWorkItem kogitoWorkItem, Policy... policies) {
        try {
            Stream.of(policies).forEach(p -> p.enforce(kogitoWorkItem));
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    @Override
    public void completeWorkItem(String workItemId, Map<String, Object> variables, Policy... policies) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            syncWorkItems();
            getProcessRuntime().getKogitoProcessRuntime().getKogitoWorkItemManager().completeWorkItem(workItemId, variables, policies);
            removeOnFinish();
            return null;
        });
    }

    @Override
    public <R> R updateWorkItem(String workItemId, Function<KogitoWorkItem, R> updater, Policy... policies) {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            syncWorkItems();
            R result = getProcessRuntime().getKogitoProcessRuntime().getKogitoWorkItemManager().updateWorkItem(workItemId, updater, policies);
            ((MutableProcessInstances<T>) process.instances()).update(this.id(), this);
            return result;
        });
    }

    @Override
    public void abortWorkItem(String workItemId, Policy... policies) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            syncWorkItems();
            getProcessRuntime().getKogitoProcessRuntime().getKogitoWorkItemManager().abortWorkItem(workItemId, policies);
            removeOnFinish();
            return null;
        });
    }

    @Override
    public void transitionWorkItem(String workItemId, WorkItemTransition transition) {
        processInstanceLockStrategy.executeOperation(id, () -> {
            syncWorkItems();
            getProcessRuntime().getKogitoProcessRuntime().getKogitoWorkItemManager().transitionWorkItem(workItemId, transition);
            removeOnFinish();
            return null;
        });
    }

    private void syncWorkItems() {
        for (org.kie.api.runtime.process.NodeInstance nodeInstance : processInstance().getNodeInstances(true)) {
            if (nodeInstance instanceof WorkItemNodeInstance workItemNodeInstance) {
                workItemNodeInstance.internalRegisterWorkItem();
            }
        }
    }

    @Override
    public Set<EventDescription<?>> events() {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().getEventDescriptions();
        });
    }

    @Override
    public Collection<Milestone> milestones() {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().milestones();
        });
    }

    @Override
    public Collection<TimerDescription> timers() {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().timers();
        });
    }

    @Override
    public Collection<AdHocFragment> adHocFragments() {
        return processInstanceLockStrategy.executeOperation(id, () -> {
            return processInstance().adHocFragments();
        });
    }

    protected void removeOnFinish() {
        if (processInstance.getState() != KogitoProcessInstance.STATE_ACTIVE && processInstance.getState() != KogitoProcessInstance.STATE_ERROR) {
            removeCompletionListener();
            syncProcessInstance(processInstance);
            remove();
        } else {
            ((MutableProcessInstances<T>) process.instances()).update(this.id(), this);
        }
        unbind(this.variables, processInstance().getVariables());
        this.status = processInstance.getState();
    }

    private void remove() {
        if (removed.getAndSet(true)) {
            //already removed
            return;
        }
        correlationInstance.map(CorrelationInstance::getCorrelation).ifPresent(c -> process.correlations().delete(c));
        ((MutableProcessInstances<T>) process.instances()).remove(this.id());
    }

    // this must be overridden at compile time
    protected Map<String, Object> bind(T variables) {
        HashMap<String, Object> vmap = new HashMap<>();
        if (variables == null) {
            return vmap;
        }
        try {
            for (Field f : variables.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(variables);
                vmap.put(f.getName(), v);
            }
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
        vmap.put("$v", variables);
        return vmap;
    }

    protected void unbind(T variables, Map<String, Object> vmap) {
        if (vmap == null) {
            return;
        }
        try {
            for (Field f : variables.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                f.set(variables, vmap.get(f.getName()));
            }
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
        vmap.put("$v", variables);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + ((status == null) ? 0 : status.hashCode());
        return result;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AbstractProcessInstance other = (AbstractProcessInstance) obj;
        if (id == null) {
            if (other.id != null)
                return false;
        } else if (!id.equals(other.id))
            return false;
        if (status == null) {
            if (other.status != null)
                return false;
        } else if (!status.equals(other.status))
            return false;
        return true;
    }

    protected ProcessError buildProcessError() {
        WorkflowProcessInstance pi = processInstance();

        final String errorMessage = pi.getErrorMessage();
        final String nodeInError = pi.getNodeIdInError();
        final String nodeInstanceInError = pi.getNodeInstanceIdInError();
        final Throwable errorCause = pi.getErrorCause().orElse(null);
        return new ProcessError() {

            @Override
            public String failedNodeId() {
                return nodeInError;
            }

            @Override
            public String failedNodeInstanceId() {
                return nodeInstanceInError;
            }

            @Override
            public String errorMessage() {
                return errorMessage;
            }

            @Override
            public Throwable errorCause() {
                return errorCause;
            }

            @Override
            public void retrigger() {
                WorkflowProcessInstanceImpl pInstance = (WorkflowProcessInstanceImpl) processInstance();
                NodeInstanceImpl ni = (NodeInstanceImpl) pInstance.getByNodeDefinitionId(nodeInError, pInstance.getNodeContainer());
                clearError(pInstance);
                getProcessRuntime().getProcessEventSupport().fireProcessRetriggered(pInstance, pInstance.getKnowledgeRuntime());
                org.kie.api.runtime.process.NodeInstanceContainer nodeInstanceContainer = ni.getNodeInstanceContainer();
                if (nodeInstanceContainer instanceof NodeInstance) {
                    ((NodeInstance) nodeInstanceContainer).internalSetTriggerTime(new Date());
                }
                ni.internalSetRetrigger(true);
                ni.trigger(null, Node.CONNECTION_DEFAULT_TYPE);
                removeOnFinish();
            }

            @Override
            public void skip() {
                WorkflowProcessInstanceImpl pInstance = (WorkflowProcessInstanceImpl) processInstance();
                NodeInstanceImpl ni = (NodeInstanceImpl) pInstance.getByNodeDefinitionId(nodeInError, pInstance.getNodeContainer());
                clearError(pInstance);
                ni.triggerCompleted(Node.CONNECTION_DEFAULT_TYPE, true);
                removeOnFinish();
            }

            private void clearError(WorkflowProcessInstanceImpl pInstance) {
                pInstance.setState(STATE_ACTIVE);
                pInstance.internalSetErrorNodeId(null);
                pInstance.internalSetErrorNodeInstanceId(null);
                pInstance.internalSetErrorMessage(null);
            }
        };
    }

    private class CompletionEventListener implements KogitoEventListener {

        @Override
        public void signalEvent(String type, Object event) {
            ((WorkflowProcess) process.get()).getOutputValidator().ifPresent(v -> v.validate(processInstance.getVariables()));
            removeOnFinish();
        }

        @Override
        public String[] getEventTypes() {
            return new String[] { "processInstanceCompleted:" + processInstance.getStringId() };
        }
    }

    private class StringCorrelationKey implements CorrelationKey {

        private final String correlationKey;

        public StringCorrelationKey(String correlationKey) {
            this.correlationKey = correlationKey;
        }

        @Override
        public String getName() {
            return correlationKey;
        }

        @Override
        public List<CorrelationProperty<?>> getProperties() {
            return Collections.emptyList();
        }

        @Override
        public String toExternalForm() {
            return correlationKey;
        }

    }
}
