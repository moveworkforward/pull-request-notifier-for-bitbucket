package se.bjurr.prnfb.listener;

import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.of;
import static com.google.common.collect.Maps.newHashMap;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Logger.getLogger;
import static java.util.regex.Pattern.compile;
import static se.bjurr.prnfb.admin.AdminFormValues.TRIGGER_IF_MERGE.ALWAYS;
import static se.bjurr.prnfb.admin.AdminFormValues.TRIGGER_IF_MERGE.CONFLICTING;
import static se.bjurr.prnfb.admin.AdminFormValues.TRIGGER_IF_MERGE.NOT_CONFLICTING;
import static se.bjurr.prnfb.listener.PrnfbPullRequestAction.fromPullRequestEvent;
import static se.bjurr.prnfb.listener.PrnfbRenderer.PrnfbVariable.PULL_REQUEST_COMMENT_TEXT;
import static se.bjurr.prnfb.listener.PrnfbRenderer.PrnfbVariable.PULL_REQUEST_MERGE_COMMIT;
import static se.bjurr.prnfb.listener.UrlInvoker.urlInvoker;
import static se.bjurr.prnfb.settings.SettingsStorage.getPrnfbSettings;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import se.bjurr.prnfb.listener.PrnfbRenderer.PrnfbVariable;
import se.bjurr.prnfb.settings.Header;
import se.bjurr.prnfb.settings.PrnfbNotification;
import se.bjurr.prnfb.settings.PrnfbSettings;
import se.bjurr.prnfb.settings.ValidationException;

import com.atlassian.bitbucket.event.pull.PullRequestApprovedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestCommentAddedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestCommentEvent;
import com.atlassian.bitbucket.event.pull.PullRequestCommentRepliedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestDeclinedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestEvent;
import com.atlassian.bitbucket.event.pull.PullRequestMergedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestOpenedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestReopenedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestRescopedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestUnapprovedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestUpdatedEvent;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.event.api.EventListener;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;

public class PrnfbPullRequestEventListener {

 private final PluginSettingsFactory pluginSettingsFactory;
 private final RepositoryService repositoryService;
 private final ApplicationPropertiesService propertiesService;
 private final PullRequestService pullRequestService;
 private final ExecutorService executorService;
 private static final Logger logger = getLogger(PrnfbPullRequestEventListener.class.getName());

 private static Invoker invoker = urlInvoker -> urlInvoker.invoke();

 @VisibleForTesting
 public static void setInvoker(Invoker invoker) {
  PrnfbPullRequestEventListener.invoker = invoker;
 }

 public PrnfbPullRequestEventListener(PluginSettingsFactory pluginSettingsFactory, RepositoryService repositoryService,
   ApplicationPropertiesService propertiesService, PullRequestService pullRequestService,
   ExecutorService executorService) {
  this.pluginSettingsFactory = pluginSettingsFactory;
  this.repositoryService = repositoryService;
  this.propertiesService = propertiesService;
  this.pullRequestService = pullRequestService;
  this.executorService = executorService;
 }

 @EventListener
 public void onEvent(PullRequestApprovedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestCommentAddedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestCommentRepliedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestDeclinedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestMergedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestOpenedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestReopenedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(final PullRequestRescopedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestUnapprovedEvent e) {
  handleEventAsync(e);
 }

 @EventListener
 public void onEvent(PullRequestUpdatedEvent e) {
  handleEventAsync(e);
 }

 @VisibleForTesting
 public void handleEventAsync(final PullRequestEvent pullRequestEvent) {
  executorService.execute(new Runnable() {
   @Override
   public void run() {
    handleEvent(pullRequestEvent);
   }
  });
 }

 private void handleEvent(final PullRequestEvent pullRequestEvent) {
  try {
   if (pullRequestEvent.getPullRequest().isClosed() && pullRequestEvent instanceof PullRequestCommentEvent) {
    return;
   }
   final PrnfbSettings settings = getPrnfbSettings(pluginSettingsFactory.createGlobalSettings());
   for (final PrnfbNotification notification : settings.getNotifications()) {
    PrnfbPullRequestAction action = fromPullRequestEvent(pullRequestEvent, notification);
    Map<PrnfbVariable, Supplier<String>> variables = newHashMap();
    if (pullRequestEvent instanceof PullRequestCommentAddedEvent) {
     variables.put(PULL_REQUEST_COMMENT_TEXT, () -> ((PullRequestCommentAddedEvent) pullRequestEvent).getComment()
       .getText());
    } else if (pullRequestEvent instanceof PullRequestMergedEvent) {
     variables.put(PULL_REQUEST_MERGE_COMMIT, new Supplier<String>() {
      @Override
      public String get() {
       return ((PullRequestMergedEvent) pullRequestEvent).getCommit().getId();
      }
     });
    }
    PrnfbRenderer renderer = new PrnfbRenderer(pullRequestEvent.getPullRequest(), action, pullRequestEvent.getUser(),
      repositoryService, propertiesService, notification, variables);
    notify(notification, action, pullRequestEvent.getPullRequest(), variables, renderer);
   }
  } catch (final ValidationException e) {
   logger.log(SEVERE, "", e);
  }
 }

 public void notify(final PrnfbNotification notification, PrnfbPullRequestAction pullRequestAction,
   PullRequest pullRequest, Map<PrnfbVariable, Supplier<String>> variables, PrnfbRenderer renderer) {
  if (!notificationTriggeredByAction(notification, pullRequestAction, renderer, pullRequest)) {
   return;
  }

  Optional<String> postContent = absent();
  if (notification.getPostContent().isPresent()) {
   postContent = of(renderer.render(notification.getPostContent().get(), FALSE));
  }
  String renderedUrl = renderer.render(notification.getUrl(), TRUE);
  logger.info(notification.getName() + " > " //
    + pullRequest.getFromRef().getId() + "(" + pullRequest.getFromRef().getLatestCommit() + ") -> " //
    + pullRequest.getToRef().getId() + "(" + pullRequest.getToRef().getLatestCommit() + ")" + " " //
    + renderedUrl);
  UrlInvoker urlInvoker = urlInvoker()//
    .withUrlParam(renderedUrl)//
    .withMethod(notification.getMethod())//
    .withPostContent(postContent)//
    .appendBasicAuth(notification);
  for (Header header : notification.getHeaders()) {
   urlInvoker//
     .withHeader(header.getName(), renderer.render(header.getValue(), FALSE));
  }
  invoker.invoke(urlInvoker//
    .withProxyServer(notification.getProxyServer()) //
    .withProxyPort(notification.getProxyPort())//
    .withProxyUser(notification.getProxyUser())//
    .withProxyPassword(notification.getProxyPassword()));
 }

 public boolean notificationTriggeredByAction(PrnfbNotification notification, PrnfbPullRequestAction pullRequestAction,
   PrnfbRenderer renderer, PullRequest pullRequest) {
  if (!notification.getTriggers().contains(pullRequestAction)) {
   return FALSE;
  }
  if (notification.getFilterRegexp().isPresent()
    && notification.getFilterString().isPresent()
    && !compile(notification.getFilterRegexp().get()).matcher(
      renderer.render(notification.getFilterString().get(), FALSE)).find()) {
   return FALSE;
  }

  if (notification.getTriggerIgnoreStateList().contains(pullRequest.getState())) {
   return FALSE;
  }

  if (notification.getTriggerIfCanMerge() != ALWAYS && pullRequest.isOpen()) {
   // Cannot perform canMerge unless PR is open
   boolean isConflicted = pullRequestService.canMerge(pullRequest.getToRef().getRepository().getId(),
     pullRequest.getId()).isConflicted();
   if (notification.getTriggerIfCanMerge() == NOT_CONFLICTING && isConflicted || //
     notification.getTriggerIfCanMerge() == CONFLICTING && !isConflicted) {
    return FALSE;
   }
  }

  return TRUE;
 }
}