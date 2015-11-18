package org.cloudfoundry.autosleep.servicebroker.service;

import lombok.extern.slf4j.Slf4j;
import org.cloudfoundry.autosleep.dao.model.ApplicationBinding;
import org.cloudfoundry.autosleep.dao.model.ApplicationInfo;
import org.cloudfoundry.autosleep.dao.model.AutosleepServiceInstance;
import org.cloudfoundry.autosleep.dao.repositories.ApplicationRepository;
import org.cloudfoundry.autosleep.dao.repositories.BindingRepository;
import org.cloudfoundry.autosleep.dao.repositories.ServiceRepository;
import org.cloudfoundry.autosleep.scheduling.GlobalWatcher;
import org.cloudfoundry.community.servicebroker.exception.ServiceBrokerException;
import org.cloudfoundry.community.servicebroker.exception.ServiceInstanceBindingExistsException;
import org.cloudfoundry.community.servicebroker.model.CreateServiceInstanceBindingRequest;
import org.cloudfoundry.community.servicebroker.model.DeleteServiceInstanceBindingRequest;
import org.cloudfoundry.community.servicebroker.model.ServiceInstanceBinding;
import org.cloudfoundry.community.servicebroker.service.ServiceInstanceBindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@Service
@Slf4j
public class AutoSleepServiceInstanceBindingService implements ServiceInstanceBindingService {

    private ApplicationRepository appRepository;

    private ServiceRepository serviceRepository;

    private BindingRepository bindingRepository;

    private GlobalWatcher watcher;

    @Autowired
    public AutoSleepServiceInstanceBindingService(ApplicationRepository appRepository,
                                                  ServiceRepository serviceRepository,
                                                  BindingRepository bindingRepository, GlobalWatcher watcher) {
        this.appRepository = appRepository;
        this.serviceRepository = serviceRepository;
        this.bindingRepository = bindingRepository;
        this.watcher = watcher;
    }

    @Override
    public ServiceInstanceBinding createServiceInstanceBinding(CreateServiceInstanceBindingRequest request) throws
            ServiceInstanceBindingExistsException, ServiceBrokerException {

        String bindingId = request.getBindingId();
        String serviceId = request.getServiceInstanceId();
        String appId = request.getAppGuid();
        log.debug("createServiceInstanceBinding - {}", request.getBindingId());

        ApplicationInfo appInfo = appRepository.findOne(appId);
        if (appInfo == null) {
            appInfo = new ApplicationInfo(UUID.fromString(appId),serviceId);
        } else {
            appInfo.getStateMachine().onOptIn();
        }

        //retrieve service to return its params as credentials
        AutosleepServiceInstance serviceInstance = serviceRepository.findOne(serviceId);
        Map<String,Object> credentials = new HashMap<>();
        credentials.put(AutosleepServiceInstance.INACTIVITY_PARAMETER,serviceInstance.getInterval().toString());
        credentials.put(AutosleepServiceInstance.NO_OPTOUT_PARAMETER,serviceInstance.isNoOptOut());
        credentials.put(AutosleepServiceInstance.EXCLUDE_PARAMETER,serviceInstance.getExcludeNames());

        ApplicationBinding binding = new ApplicationBinding(bindingId,
                serviceId,
                credentials,
                null,
                appId);
        bindingRepository.save(binding);
        appRepository.save(appInfo);
        watcher.watchApp(binding);
        return binding;
    }

    @Override
    public ServiceInstanceBinding deleteServiceInstanceBinding(DeleteServiceInstanceBindingRequest request)
            throws ServiceBrokerException {
        String bindingId = request.getBindingId();
        AutosleepServiceInstance serviceInstance = serviceRepository
                .findOne(request.getInstance().getServiceInstanceId());
        if (serviceInstance.isNoOptOut()) {
            throw new ServiceBrokerException("unbinding this service is forbidden");
        } else {
            log.debug("deleteServiceInstanceBinding - {}", bindingId);
            ApplicationBinding binding = bindingRepository.findOne(bindingId);
            log.debug("deleteServiceInstanceBinding on app ", binding.getAppGuid());
            ApplicationInfo appInfo = appRepository.findOne(binding.getAppGuid());
            if (appInfo != null) {
                log.error("Deleting a binding with no related application info. This should never happen.");
                appInfo.getStateMachine().onOptOut();
            }
            bindingRepository.delete(bindingId);
            appRepository.save(appInfo);
            //task launched will cancel by itself
            return binding;
        }
    }
}
