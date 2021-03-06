package com.tapadoo.slacknotifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jetbrains.buildServer.issueTracker.Issue;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.settings.ProjectSettingsManager;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserSet;
import jetbrains.buildServer.vcs.SelectPrevBuildPolicy;
import org.joda.time.Duration;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;


/**
 * Created by jasonconnery on 02/03/2014.
 */
public class SlackServerAdapter extends BuildServerAdapter {

    private final SBuildServer buildServer;
    private final SlackConfigProcessor slackConfig;
    private final ProjectSettingsManager projectSettingsManager;

    private Gson gson;

    public SlackServerAdapter(SBuildServer sBuildServer, ProjectManager projectManager, ProjectSettingsManager projectSettingsManager, SlackConfigProcessor configProcessor) {
        this.projectSettingsManager = projectSettingsManager;
        this.buildServer = sBuildServer;
        this.slackConfig = configProcessor;
    }

    public void init() {
        buildServer.addListener(this);
    }

    private Gson getGson() {
        if (gson == null) {
            gson = new GsonBuilder().create();
        }

        return gson;
    }

    @Override
    public void buildStarted(SRunningBuild build) {
        super.buildStarted(build);

        SlackProjectSettings projectSettings = getProjectSettings(build);

        if (!build.isPersonal() && (
                (!projectSettings.postStartedSet() && slackConfig.postStarted()) ||
                        (projectSettings.postStartedSet() && projectSettings.postStartedEnabled())
        )) {
            String message = String.format("Project '%s' build started.", build.getFullName());

            postToSlack(build, message, true);
        }
    }

    @Override
    public void buildFinished(SRunningBuild build) {
        super.buildFinished(build);

        if (build.getBuildStatus().isSuccessful()) {
            processSuccessfulBuild(build);
            return;
        }

        if (build.getBuildStatus().isFailed()) {
            processFailedBuild(build);
        }
    }

    private void processFailedBuild(SRunningBuild build) {
        if (build == null)
            return;

        if (build.getBranch() == null)
            return;

        SlackProjectSettings projectSettings = getProjectSettings(build);

        if (!build.isPersonal() && (
                (!projectSettings.postFailedSet() && slackConfig.postFailed()) ||
                        (projectSettings.postFailedSet() && projectSettings.postFailedEnabled())
        )) {
            String message;

            BuildDuration buildDuration = new BuildDuration(build);

            String buildFailedPermalink = this.slackConfig.getBuildFailedPermalink();

            if (buildFailedPermalink != null && buildFailedPermalink.length() > 0)
                message = String.format("Project '%s' (%s) build failed! ( %s )\n%s",
                        build.getFullName(),
                        build.getBranch().getDisplayName(),
                        buildDuration,
                        buildFailedPermalink
                );
            else
                message = String.format("Project '%s' (%s) build failed! ( %s )",
                        build.getFullName(),
                        build.getBranch().getDisplayName(),
                        buildDuration
                );

            postToSlack(build, message, false);
        }
    }

    private void processSuccessfulBuild(SRunningBuild build) {
        if (build == null)
            return;

        if (build.getBranch() == null)
            return;

        SlackProjectSettings projectSettings = getProjectSettings(build);

        if (!build.isPersonal() && (
                (!projectSettings.postSuccessfulSet() && slackConfig.postSuccessful()) ||
                        (projectSettings.postSuccessfulSet() && projectSettings.postSuccessfulEnabled())
        )) {

            BuildDuration buildDuration = new BuildDuration(build);
            String message = String.format("Project '%s' (%s) built successfully in %s.", build.getFullName(), build.getBranch().getDisplayName(), buildDuration);

            postToSlack(build, message, true);
        }
    }

    /**
     * Post a payload to slack with a message and good/bad color. Committer summary is automatically added as an attachment
     *
     * @param build     the build the message is relating to
     * @param message   main message to include, 'Build X completed...' etc
     * @param goodColor true for 'good' builds, false for danger.
     */
    private void postToSlack(SRunningBuild build, String message, boolean goodColor) {
        try {

            URL url = new URL(slackConfig.getPostUrl());

            SlackProjectSettings projectSettings = getProjectSettings(build);

            if (!projectSettings.isEnabled())
                return;

            String iconUrl = projectSettings.getLogoUrl();

            if (iconUrl == null || iconUrl.length() < 1) {
                iconUrl = slackConfig.getLogoUrl();
            }

            String configuredChannel = build.getParametersProvider().get("SLACK_CHANNEL");
            String channel = this.slackConfig.getDefaultChannel();

            if (configuredChannel != null && configuredChannel.length() > 0) {
                channel = configuredChannel;
            } else if (projectSettings.getChannel() != null && projectSettings.getChannel().length() > 0) {
                channel = projectSettings.getChannel();
            }

            UserSet<SUser> committers = build.getCommitters(SelectPrevBuildPolicy.SINCE_LAST_BUILD);
            StringBuilder committersString = new StringBuilder();

            for (SUser committer : committers.getUsers()) {
                if (committer != null) {
                    String committerName = committer.getName();
                    if (committerName == null || committerName.equals("")) {
                        committerName = committer.getUsername();
                    }

                    if (committerName != null && !committerName.equals("")) {
                        committersString.append(committerName);
                        committersString.append(",");
                    }
                }
            }

            if (committersString.length() > 0) {
                committersString.deleteCharAt(committersString.length() - 1); //remove the last ,
            }

            String commitMsg = committersString.toString();

            JsonObject payloadObj = new JsonObject();
            payloadObj.addProperty("channel", channel);
            payloadObj.addProperty("username", "TeamCity");
            payloadObj.addProperty("text", message);
            payloadObj.addProperty("icon_url", iconUrl);

            JsonArray attachmentsObj = new JsonArray();

            if (commitMsg.length() > 0) {
                JsonObject attachment = new JsonObject();

                attachment.addProperty("fallback", "Changes by" + commitMsg);
                attachment.addProperty("color", (goodColor ? "good" : "danger"));

                JsonArray fields = new JsonArray();
                JsonObject field = new JsonObject();

                field.addProperty("title", "Changes By");
                field.addProperty("value", commitMsg);
                field.addProperty("short", true);

                fields.add(field);
                attachment.add("fields", fields);

                attachmentsObj.add(attachment);

            }

            //Do we have any issues?

            if (build.isHasRelatedIssues()) {
                //We do!
                Collection<Issue> issues = build.getRelatedIssues();
                JsonObject issuesAttachment = new JsonObject();

                StringBuilder issueIds = new StringBuilder();
                StringBuilder clickableIssueIds = new StringBuilder();

                for (Issue issue : issues) {
                    issueIds.append(',');
                    issueIds.append(issue.getId());

                    clickableIssueIds.append(',');

                    clickableIssueIds.append('<');
                    clickableIssueIds.append(issue.getUrl());
                    clickableIssueIds.append('|');
                    clickableIssueIds.append(issue.getId());
                    clickableIssueIds.append('>');
                }

                if (issueIds.length() > 0) {
                    issueIds.deleteCharAt(0); //delete first ','
                }

                if (clickableIssueIds.length() > 0) {
                    clickableIssueIds.deleteCharAt(0); //delete first ','
                }

                issuesAttachment.addProperty("fallback", "Issues " + issueIds.toString());
                //Not sure what color, if any to use for this. For now, leave it the same as the committers one
                issuesAttachment.addProperty("color", (goodColor ? "good" : "danger"));

                JsonArray fields = new JsonArray();
                JsonObject field = new JsonObject();

                field.addProperty("title", "Related Issues");
                field.addProperty("value", clickableIssueIds.toString());
                field.addProperty("short", true);

                fields.add(field);
                issuesAttachment.add("fields", fields);

                attachmentsObj.add(issuesAttachment);
            }

            if (attachmentsObj.size() > 0) {
                payloadObj.add("attachments", attachmentsObj);
            }

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);

            BufferedOutputStream bos = new BufferedOutputStream(conn.getOutputStream());

            String payloadJson = getGson().toJson(payloadObj);
            String bodyContents = "payload=" + payloadJson;

            bos.write(bodyContents.getBytes("utf8"));
            bos.flush();
            bos.close();

            conn.disconnect();

        } catch (MalformedURLException ex) {

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private SlackProjectSettings getProjectSettings(SRunningBuild build) {
        return (SlackProjectSettings) projectSettingsManager.getSettings(build.getProjectId(), "slackSettings");
    }
}
