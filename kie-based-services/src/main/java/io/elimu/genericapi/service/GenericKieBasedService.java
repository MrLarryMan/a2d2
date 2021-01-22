package io.elimu.genericapi.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.drools.core.ClassObjectFilter;
import org.drools.core.command.impl.CommandBasedStatefulKnowledgeSession;
import org.drools.core.impl.KnowledgeBaseImpl;
import org.drools.core.impl.StatefulKnowledgeSessionImpl;
import org.drools.persistence.PersistableRunner;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.process.WorkflowProcessInstance;
import org.kie.api.task.TaskService;
import org.kie.api.task.model.Status;
import org.kie.api.task.model.Task;
import org.kie.internal.runtime.manager.context.ProcessInstanceIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.elimu.a2d2.cdsmodel.Dependency;
import io.elimu.a2d2.genericmodel.ServiceRequest;
import io.elimu.a2d2.genericmodel.ServiceResponse;
import io.elimu.a2d2.process.ServiceUtils;
import io.elimu.genericapi.plugins.ModulePluginLoader;
import io.elimu.genericapi.scrubbers.ScrubberLocator;
import io.elimu.serviceapi.service.AbstractKieService;
import io.elimu.serviceapi.service.AppContextUtils;
import io.elimu.serviceapi.service.ProcessVariableInitHelper;

public class GenericKieBasedService extends AbstractKieService implements GenericService {

	private static final String NOT_ALLOWED_METHOD_KEY = "____NOT_ALLOWED";
	private static final Logger LOG = LoggerFactory.getLogger(GenericKieBasedService.class);
	private static final String SERVICECATEGORY = "serviceCategory";
	private static final String KIE_PROJECT_PROCESSID = "kie.project.processid.";
	private static final List<String> EXISTING_METHODS = Arrays.asList("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE");

	private String processId;
	private boolean logExecution = false;
	private Properties config;
	ProcessVariableInitHelper processVariableHelper;
	private JSONObject discoveryDsl;
	
	public JSONObject getDiscoveryDsl() {
		return discoveryDsl;
	}

	public void setDiscoveryDsl(JSONObject discoveryDsl) {
		this.discoveryDsl = discoveryDsl;
	}

	public GenericKieBasedService() {
	}
	
	public GenericKieBasedService(String releaseId, String defaultCustomer) {
		super(releaseId, defaultCustomer);
	}
	
	public GenericKieBasedService(Dependency dep) {
		super(dep);
	}

	public void stop() {
		super.stop();
		this.processId = null;
	}
	
	@Override
	protected void init() {
		super.init();
		if (getDefaultCustomer() == null) {
			setDefaultCustomer(RunningServices.getInstance().getSpace(getDependency()));
		}
		String appConfigFile = System.getProperty("kie.apps.config.folder", 
				ServiceUtils.getDefaultConfigFolder()) + "/" + getId() + ".properties";
		try {
			this.config = ServiceUtils.getConfig(getDependency(), AppContextUtils.getInstance().getProfileName());
			if (this.config.containsKey(SERVICECATEGORY)) {
				setServiceCategory(this.config.getProperty(SERVICECATEGORY).trim());
			}
		} catch (IOException e) {
			throw new GenericServiceConfigException("File " + appConfigFile + " found, but couldn't be read. Service " + getId() + " not loaded.", e);
		}
		processVariableHelper = new ProcessVariableInitHelper();
		URL jarPath = ServiceUtils.toJarPath(getDependency());
		//priority 1: localized service properties file
		//configure a system property pointing to the server's config folder
		InputStream discoveryEntry = ServiceUtils.readEntry(jarPath, "discovery.dsl");
		if (discoveryEntry != null) {
			try {
				this.discoveryDsl = (JSONObject) new JSONParser().parse(new InputStreamReader(discoveryEntry, "UTF-8"));
			} catch (IOException | ParseException e) {
				LOG.error("Exception in parsing discoveryEntry", e);
			}
		}
		this.processId = extractProcessId(jarPath, config, "kie.project.processId");
		this.logExecution = Boolean.valueOf(config.getProperty("kie.project.logexec", "false").toLowerCase());
		String pluginEntries = config.getProperty("kie.project.module.plugins", "FtlLoader");
		if (pluginEntries != null) {
			for (String plugin : pluginEntries.split(",")) {
				ModulePluginLoader.get(getClassLoader(), plugin).process(getDependency());
			}
		}
	}


	@Override
	public ServiceResponse execute(ServiceRequest request) throws GenericServiceException {
		if (!ignoreScrubbing()) {
			ScrubberLocator.getInstance().getScrubber(request).scrub(request);
		}
		RuntimeEngine runtime = null;
		ClassLoader oldCL = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(getKieContainer().getClassLoader());
			runtime = getManager().getRuntimeEngine(ProcessInstanceIdContext.get());
			KieSession ksession = runtime.getKieSession();
			String procId = getProcessId(request.getMethod());
			if (procId != null && !NOT_ALLOWED_METHOD_KEY.equals(procId)) {
				LOG.info("Executing process " + procId + " for service " + getId());
				Map<String, Object> params = new HashMap<>();
				params.put("serviceRequest", request);
				params.put("serviceResponse", defaultResponse());
				params.putAll(processVariableHelper.initVariables(getDependency()));
				WorkflowProcessInstance instance = (WorkflowProcessInstance) ksession.startProcess(procId, params);
				ServiceResponse response = (ServiceResponse) instance.getVariable("serviceResponse");
				if (response == null) {
					return new ServiceResponse("There were 0 ServiceResponse objects obtained from execution.", 400);
				}
				return response;
			} else if (!NOT_ALLOWED_METHOD_KEY.equals(procId)) {
				LOG.info("Executing rules directly for service " + getId());
				ksession.insert(request);
				ksession.fireAllRules();
				Collection<?> retvals = ksession.getObjects(new ClassObjectFilter(ServiceResponse.class));
				if (retvals.size() == 1) {
					return (ServiceResponse) retvals.iterator().next();
				} else {
					return new ServiceResponse("There were " + retvals.size() + " ServiceResponse objects obtained from execution. Only 1 is allowed", 400);
				}
			} else {
				return new ServiceResponse("Method " + request.getMethod() + " not allowed", 405);
			}
		} catch (Exception e) {
			LOG.error("Problem executing service", e);
			throw new GenericServiceException(
					"The execution of the service encountered an error of type " 
							+ e.getClass().getSimpleName() + " and stopped. Further"
							+ " information can be found in the server logs. Contact "
							+ "your system administrator.", e);
		} finally {
			cleanup(runtime, oldCL);
		}
	}
	
	private boolean ignoreScrubbing() {
		Predicate<? super Object> f = k -> k.toString().equalsIgnoreCase("kie.project.ignorescrub"); 
		Optional<Object> ignorescrub = config.keySet().stream().filter(f).findFirst();
		if (ignorescrub.isPresent()) {
			return "true".equalsIgnoreCase(config.getProperty(ignorescrub.get().toString()));
		}
		return false;
	}
	
	private String getProcessId(String method) {
		if (method == null) {
			return processId;
		}
		Optional<Object> kieProcessId = config.keySet().stream().
				filter(k -> k.toString().toLowerCase().startsWith(KIE_PROJECT_PROCESSID + method.toLowerCase())).findFirst();
		if (kieProcessId.isPresent()) {
			return config.getProperty(kieProcessId.get().toString());
		} else if (config.keySet().stream().
				filter(k -> k.toString().toLowerCase().startsWith(KIE_PROJECT_PROCESSID)).count() > 0) {
			return NOT_ALLOWED_METHOD_KEY;
		} else {
			return processId;
		}
	}

	private void cleanup(RuntimeEngine runtime, ClassLoader oldCL) {
		if (runtime == null) {
			return;
		}
		KieSession ksession = runtime.getKieSession();
		if (ksession != null) {
			((KnowledgeBaseImpl) getKieBase())
				.disposeStatefulSession(
						asInternalKieSession(ksession));
		}
		
		getManager().disposeRuntimeEngine(runtime);
		
		if (oldCL != null) {
			Thread.currentThread().setContextClassLoader(oldCL);
		}
	}

	private StatefulKnowledgeSessionImpl asInternalKieSession(KieSession ksession) {
		if (ksession instanceof StatefulKnowledgeSessionImpl) {
			return (StatefulKnowledgeSessionImpl) ksession;
		}
		if (ksession instanceof CommandBasedStatefulKnowledgeSession) {
			CommandBasedStatefulKnowledgeSession cmdSession = (CommandBasedStatefulKnowledgeSession) ksession;
			PersistableRunner runner = (PersistableRunner) cmdSession.getRunner();
			return (StatefulKnowledgeSessionImpl) runner.getKieSession();
		} 
		return null;
	}

	private ServiceResponse defaultResponse() {
		return new ServiceResponse("Process for service " + getDependency().getArtifactId() + " is getting started", 200);
	}

	@Override
	protected boolean shouldLogExecution() {
		return logExecution;
	}

	public Properties getConfig() {
		if (this.config == null && getDependency() != null) {
			try {
				this.config = ServiceUtils.getConfig(getDependency(), AppContextUtils.getInstance().getProfileName());
			} catch (Exception e) {
				LOG.warn("COULD NOT INITIALIZE CONFIG ON DEMAND", e);
			}
		}
		return config;
	}
	
	public List<String> getServiceTypes() {
		String pkgNamesString = config.getProperty("cds.based.project.packages");
		if (pkgNamesString == null) {
			return new ArrayList<>();
		}
		return Arrays.asList(pkgNamesString.split(","));
	}
	
	/**
	 * @return if no http method specific entry is provided, serve all cases. Otherwise serve only specified methods
	 */
	public List<String> getAvailableMethods() {
		List<String> values = config.keySet().stream().
				filter(k -> k.toString().toLowerCase().startsWith(KIE_PROJECT_PROCESSID)).
				map(k -> k.toString().toUpperCase().replace(KIE_PROJECT_PROCESSID, "")).collect(Collectors.toList());
		return values == null ? EXISTING_METHODS : values;
	}
	
	@Override
	public void updateTask(Task task) throws GenericServiceException {
		RuntimeEngine runtime = getManager().getRuntimeEngine(ProcessInstanceIdContext.get(task.getTaskData().getProcessInstanceId()));
		TaskService taskService = runtime.getTaskService();
		if (taskService != null) {
			taskService.execute(new InternalUpdateTaskCommand(task));
			if (task.getTaskData().getStatus() == Status.Completed) {
				runtime.getKieSession().getWorkItemManager().completeWorkItem(
						task.getTaskData().getWorkItemId(), 
						task.getTaskData().getTaskOutputVariables());
			} else if (task.getTaskData().getStatus() == Status.Exited) {
				runtime.getKieSession().getWorkItemManager().abortWorkItem(task.getTaskData().getWorkItemId());
			}
		}
	}

	@Override
	public Task getTask(Long taskId) {
		return getManager().getRuntimeEngine(ProcessInstanceIdContext.get()).getTaskService().getTaskById(taskId);
	}

}