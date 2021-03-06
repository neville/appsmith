package com.appsmith.server.services;

import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Action;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.ApplicationPage;
import com.appsmith.server.domains.Layout;
import com.appsmith.server.domains.Organization;
import com.appsmith.server.domains.Page;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.ApplicationPagesDTO;
import com.appsmith.server.dtos.PageNameIdDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.repositories.ApplicationRepository;
import com.google.common.base.Strings;
import com.mongodb.client.result.UpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.appsmith.server.acl.AclPermission.MANAGE_ACTIONS;
import static com.appsmith.server.acl.AclPermission.MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.MANAGE_PAGES;
import static com.appsmith.server.acl.AclPermission.ORGANIZATION_MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.READ_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.READ_PAGES;

@Slf4j
@Service
public class ApplicationPageServiceImpl implements ApplicationPageService {
    private final ApplicationService applicationService;
    private final PageService pageService;
    private final SessionUserService sessionUserService;
    private final OrganizationService organizationService;
    private final LayoutActionService layoutActionService;

    private final AnalyticsService analyticsService;
    private final PolicyGenerator policyGenerator;

    private final ApplicationRepository applicationRepository;
    private final ActionService actionService;

    public ApplicationPageServiceImpl(ApplicationService applicationService,
                                      PageService pageService,
                                      SessionUserService sessionUserService,
                                      OrganizationService organizationService,
                                      LayoutActionService layoutActionService,
                                      AnalyticsService analyticsService,
                                      PolicyGenerator policyGenerator,
                                      ApplicationRepository applicationRepository,
                                      ActionService actionService) {
        this.applicationService = applicationService;
        this.pageService = pageService;
        this.sessionUserService = sessionUserService;
        this.organizationService = organizationService;
        this.layoutActionService = layoutActionService;
        this.analyticsService = analyticsService;
        this.policyGenerator = policyGenerator;
        this.applicationRepository = applicationRepository;
        this.actionService = actionService;
    }

    public Mono<Page> createPage(Page page) {
        if (page.getId() != null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ID));
        } else if (page.getName() == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.NAME));
        } else if (page.getApplicationId() == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.APPLICATION_ID));
        }

        List<Layout> layoutList = page.getLayouts();
        if (layoutList == null) {
            layoutList = new ArrayList<>();
        }
        if (layoutList.isEmpty()) {
            layoutList.add(pageService.createDefaultLayout());
            page.setLayouts(layoutList);
        }

        Mono<Application> applicationMono = applicationService.findById(page.getApplicationId(), AclPermission.MANAGE_APPLICATIONS)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION_ID, page.getApplicationId())));

        Mono<User> userMono = sessionUserService.getCurrentUser();
        Mono<Page> pageMono = Mono.zip(applicationMono, userMono)
                .map(tuple -> {
                    Application application = tuple.getT1();
                    User user = tuple.getT2();
                    generateAndSetPagePolicies(application, user, page);
                    return page;
                });

        return pageMono
                .flatMap(pageService::createDefault)
                //After the page has been saved, update the application (save the page id inside the application)
                .zipWith(applicationMono)
                .flatMap(tuple -> {
                    final Page savedPage = tuple.getT1();
                    final Application application = tuple.getT2();
                    return addPageToApplication(application, savedPage, false)
                            .thenReturn(savedPage);
                });
    }

    /**
     * This function is called during page create in Page Service. It adds the given page to its ApplicationPages list.
     * Note: It is assumed here that `application` is already checked for the MANAGE_APPLICATIONS policy.
     *
     * @param application Application to which the page will be added. Should have an `id` already.
     * @param page Page to be added to the application. Should have an `id` already.
     * @return UpdateResult object with details on how many documents have been updated, which should be 0 or 1.
     */
    @Override
    public Mono<UpdateResult> addPageToApplication(Application application, Page page, Boolean isDefault) {
        return applicationRepository.addPageToApplication(application.getId(), page.getId(), isDefault)
                .doOnSuccess(result -> {
                    if (result.getModifiedCount() != 1) {
                        log.error("Add page to application didn't update anything, probably because application wasn't found.");
                    }
                });
    }

    @Override
    public Mono<Page> getPage(String pageId, boolean viewMode) {
        AclPermission permission = viewMode ? READ_PAGES : MANAGE_PAGES;
        return pageService.findById(pageId, permission)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.PAGE, pageId)))
                .map(page -> {
                    List<Layout> layoutList = page.getLayouts();
                    // Set the view mode for all the layouts in the page. This ensures that we send the correct DSL
                    // back to the client
                    layoutList.stream()
                            .forEach(layout -> layout.setViewMode(viewMode));
                    page.setLayouts(layoutList);
                    return page;
                });
    }

    @Override
    public Mono<Page> getPageByName(String applicationName, String pageName, boolean viewMode) {
        AclPermission appPermission;
        AclPermission pagePermission;
        if (viewMode) {
            //If view is set, then this user is trying to view the application
            appPermission = READ_APPLICATIONS;
            pagePermission = READ_PAGES;
        } else {
            appPermission = MANAGE_APPLICATIONS;
            pagePermission = MANAGE_PAGES;
        }

        return applicationService
                .findByName(applicationName, appPermission)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.PAGE + "by application name", applicationName)))
                .flatMap(application -> pageService.findByNameAndApplicationId(pageName, application.getId(), pagePermission))
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.PAGE + "by page name", pageName)))
                .map(page -> {
                    List<Layout> layoutList = page.getLayouts();
                    // Set the view mode for all the layouts in the page. This ensures that we send the correct DSL
                    // back to the client
                    layoutList.stream()
                            .forEach(layout -> layout.setViewMode(viewMode));
                    page.setLayouts(layoutList);
                    return page;
                });
    }

    @Override
    public Mono<Application> makePageDefault(Page page) {
        return makePageDefault(page.getApplicationId(), page.getId());
    }

    @Override
    public Mono<Application> makePageDefault(String applicationId, String pageId) {
        return pageService.findById(pageId, AclPermission.MANAGE_PAGES)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.PAGE, pageId)))
                // Check if the page actually belongs to the application.
                .flatMap(page -> {
                    if (page.getApplicationId().equals(applicationId)) {
                        return Mono.just(page);
                    }
                    return Mono.error(new AppsmithException(AppsmithError.PAGE_DOESNT_BELONG_TO_APPLICATION, page.getName(), applicationId));
                })
                .then(applicationService.findById(applicationId))
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION_ID, applicationId)))
                .flatMap(application ->
                        applicationRepository
                                .setDefaultPage(applicationId, pageId)
                                .then(applicationService.getById(applicationId))
                );
    }

    @Override
    public Mono<Application> createApplication(Application application) {
        return createApplication(application, application.getOrganizationId());
    }

    @Override
    public Mono<Application> createApplication(Application application, String orgId) {
        if (application.getName() == null || application.getName().trim().isEmpty()) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.NAME));
        }

        if (orgId == null || orgId.isEmpty()) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ORGANIZATION_ID));
        }

        Mono<User> userMono = sessionUserService.getCurrentUser().cache();
        Mono<Application> applicationWithPoliciesMono = setApplicationPolicies(userMono, orgId, application);

        return applicationWithPoliciesMono
                .flatMap(applicationService::createDefault)
                .zipWith(userMono)
                .flatMap(tuple -> {
                    Application savedApplication = tuple.getT1();
                    User user = tuple.getT2();

                    Page page = new Page();
                    page.setName(FieldName.DEFAULT_PAGE_NAME);
                    page.setApplicationId(savedApplication.getId());
                    List<Layout> layoutList = new ArrayList<>();
                    layoutList.add(pageService.createDefaultLayout());
                    page.setLayouts(layoutList);

                    //Set the page policies
                    generateAndSetPagePolicies(savedApplication, user, page);

                    return pageService
                            .createDefault(page)
                            .flatMap(savedPage -> addPageToApplication(savedApplication, savedPage, true))
                            .then(applicationService.findById(savedApplication.getId(), READ_APPLICATIONS));
                });
    }

    private Mono<Application> setApplicationPolicies(Mono<User> userMono, String orgId, Application application) {
        return userMono
                .flatMap(user -> {
                    Mono<Organization> orgMono = organizationService.findById(orgId, ORGANIZATION_MANAGE_APPLICATIONS)
                            .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.ORGANIZATION, orgId)));

                    return orgMono.map(org -> {
                        application.setOrganizationId(org.getId());
                        Set<Policy> documentPolicies = policyGenerator.getAllChildPolicies(org.getPolicies(), Organization.class, Application.class);
                        application.setPolicies(documentPolicies);
                        return application;
                    });
                });
    }

    @Override
    public Mono<Application> cloneExampleApplication(Application application) {
        if (!StringUtils.hasText(application.getName())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.NAME));
        }

        String orgId = application.getOrganizationId();
        if (!StringUtils.hasText(orgId)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ORGANIZATION_ID));
        }

        // Clean the object so that it will be saved as a new application for the currently signed in user.
        application.setClonedFromApplicationId(application.getId());
        application.setId(null);
        application.setPolicies(new HashSet<>());
        application.setPages(new ArrayList<>());
        application.setIsPublic(false);

        Mono<User> userMono = sessionUserService.getCurrentUser().cache();
        Mono<Application> applicationWithPoliciesMono = setApplicationPolicies(userMono, orgId, application);

        return applicationWithPoliciesMono
                .flatMap(applicationService::createDefault);
    }

    private void generateAndSetPagePolicies(Application application, User user, Page page) {
        Set<Policy> policySet = application.getPolicies().stream()
                .filter(policy -> policy.getPermission().equals(MANAGE_APPLICATIONS.getValue())
                        || policy.getPermission().equals(READ_APPLICATIONS.getValue()))
                .collect(Collectors.toSet());
        Set<Policy> documentPolicies = policyGenerator.getAllChildPolicies(policySet, Application.class, Page.class);
        page.setPolicies(documentPolicies);
    }

    /**
     * This function performs a soft delete for the application along with it's associated pages and actions.
     *
     * @param id The application id to delete
     * @return The modified application object with the deleted flag set
     */
    @Override
    public Mono<Application> deleteApplication(String id) {
        log.debug("Archiving application with id: {}", id);

        Mono<Application> applicationMono = applicationService.findById(id, MANAGE_APPLICATIONS)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, "application", id)))
                .flatMap(application -> {
                    log.debug("Archiving pages for applicationId: {}", id);
                    return pageService.findByApplicationId(id, READ_PAGES)
                            .flatMap(page -> pageService.delete(page.getId()))
                            .collectList()
                            .thenReturn(application);
                })
                .flatMap(applicationService::archive);

        return applicationMono
                .flatMap(analyticsService::sendDeleteEvent);
    }

    @Override
    public Mono<Page> clonePage(String pageId) {

        return pageService.findById(pageId, MANAGE_PAGES)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED)))
                .flatMap(page -> clonePageGivenApplicationId(pageId, page.getApplicationId(), " Copy"));
    }

    private Mono<Page> clonePageGivenApplicationId(String pageId, String applicationId,
                                                   @Nullable String newPageNameSuffix) {
        // Find the source page and then prune the page layout fields to only contain the required fields that should be
        // copied.
        Mono<Page> sourcePageMono = pageService.findById(pageId, MANAGE_PAGES)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED)))
                .flatMap(page -> Flux.fromIterable(page.getLayouts())
                        .map(layout -> layout.getDsl())
                        .map(dsl -> {
                            Layout newLayout = new Layout();
                            String id = new ObjectId().toString();
                            newLayout.setId(id);
                            newLayout.setDsl(dsl);
                            return newLayout;
                        })
                        .collectList()
                        .map(layouts -> {
                            page.setLayouts(layouts);
                            return page;
                        }));

        // This call is without
        Flux<Action> sourceActionFlux = actionService.findByPageId(pageId, MANAGE_ACTIONS)
                // In case there are no actions in the page being cloned, return empty
                .switchIfEmpty(Flux.empty());

        return sourcePageMono
                .flatMap(page -> {
                    Mono<ApplicationPagesDTO> pageNamesMono = pageService
                            .findNamesByApplicationId(page.getApplicationId());
                    return pageNamesMono
                            // If a new page name suffix is given,
                            // set a unique name for the cloned page and then create the page.
                            .flatMap(pageNames -> {
                                if (!Strings.isNullOrEmpty(newPageNameSuffix)) {
                                    String newPageName = page.getName() + newPageNameSuffix;

                                    Set<String> names = pageNames.getPages()
                                            .stream()
                                            .map(PageNameIdDTO::getName)
                                            .collect(Collectors.toSet());

                                    int i = 0;
                                    String name = newPageName;
                                    while (names.contains(name)) {
                                        i++;
                                        name = newPageName + i;
                                    }
                                    newPageName = name;

                                    page.setName(newPageName);
                                }
                                // Proceed with creating the copy of the page
                                page.setId(null);
                                page.setApplicationId(applicationId);
                                return pageService.createDefault(page);
                            });
                })
                .flatMap(page -> {
                    String newPageId = page.getId();
                    return sourceActionFlux
                            .flatMap(action -> {
                                action.setId(null);
                                action.setPageId(newPageId);
                                return actionService.create(action);
                            })
                            .collectList()
                            .thenReturn(page);
                })
                // Calculate the onload actions for this page now that the page and actions have been created
                .flatMap(savedPage -> {
                    List<Layout> layouts = savedPage.getLayouts();

                    return Flux.fromIterable(layouts)
                            .flatMap(layout -> layoutActionService.updateLayout(savedPage.getId(), layout.getId(), layout))
                            .collectList()
                            .thenReturn(savedPage);
                })
                .flatMap(page -> {
                    Mono<Application> applicationMono = applicationService.findById(page.getApplicationId(), MANAGE_APPLICATIONS);
                    return applicationMono
                            .flatMap(application -> {
                                ApplicationPage applicationPage = new ApplicationPage();
                                applicationPage.setId(page.getId());
                                applicationPage.setIsDefault(false);
                                application.getPages().add(applicationPage);
                                return applicationService.save(application)
                                        .thenReturn(page);
                            });
                });
    }

    private Mono<Page> clonePageGivenApplicationId(String pageId, String applicationId) {
        return clonePageGivenApplicationId(pageId, applicationId, null);
    }

    @Override
    public Mono<Application> cloneApplication(String applicationId) {

        Mono<Application> applicationMono = applicationService.findById(applicationId, MANAGE_APPLICATIONS)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED)))
                .cache();

        // Find the name for the cloned application which wouldn't lead to duplicate key exception
        Mono<String> newAppNameMono = applicationMono
                .flatMap(application -> applicationService.findAllApplicationsByOrganizationId(application.getOrganizationId())
                        .map(application1 -> application1.getName())
                        .collect(Collectors.toSet())
                        .map(appNames -> {
                            log.debug("app names for this organization are : {}", appNames);
                            String newAppName = application.getName() + " Copy";
                            int i = 0;
                            String name = newAppName;
                            while (appNames.contains(name)) {
                                i++;
                                name = newAppName + i;
                            }
                            return name;
                        }));

        return Mono.zip(applicationMono, newAppNameMono)
                .flatMap(tuple -> {
                    Application sourceApplication = tuple.getT1();
                    String newName = tuple.getT2();

                    sourceApplication.setId(null);
                    sourceApplication.setIsPublic(false);
                    sourceApplication.setName(newName);

                    Mono<User> userMono = sessionUserService.getCurrentUser().cache();
                    // First set the correct policies for the new cloned application
                   return setApplicationPolicies(userMono, sourceApplication.getOrganizationId(), sourceApplication)
                           // Create the cloned application with the new name and policies before proceeding further.
                           .flatMap(applicationService::createDefault)
                           // Now fetch the pages of the source application, clone and add them to this new application
                           .flatMap(savedApplication -> applicationMono
                                   .flatMap(application -> Flux.fromIterable(application.getPages())
                                           .flatMap(applicationPage -> {
                                               String pageId = applicationPage.getId();
                                               Boolean isDefault = applicationPage.getIsDefault();
                                               return this.clonePageGivenApplicationId(pageId, savedApplication.getId())
                                                       .map(page -> {
                                                           ApplicationPage newApplicationPage = new ApplicationPage();
                                                           newApplicationPage.setId(page.getId());
                                                           newApplicationPage.setIsDefault(isDefault);
                                                           return newApplicationPage;
                                                       });
                                           })
                                           .collectList()
                                   )
                                   // Set the cloned pages into the cloned application and save.
                                   .flatMap(clonedPages -> {
                                       savedApplication.setPages(clonedPages);
                                       return applicationRepository.save(savedApplication);
                                   })
                           );
                });
    }

}
