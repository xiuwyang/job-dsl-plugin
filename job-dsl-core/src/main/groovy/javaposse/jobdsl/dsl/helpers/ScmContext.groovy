package javaposse.jobdsl.dsl.helpers

import com.google.common.base.Strings
import hudson.util.VersionNumber
import javaposse.jobdsl.dsl.AbstractContext
import javaposse.jobdsl.dsl.DslContext
import javaposse.jobdsl.dsl.JobManagement
import javaposse.jobdsl.dsl.RequiresPlugin
import javaposse.jobdsl.dsl.WithXmlAction
import javaposse.jobdsl.dsl.helpers.scm.ClearCaseContext
import javaposse.jobdsl.dsl.helpers.scm.GitContext
import javaposse.jobdsl.dsl.helpers.scm.HgContext
import javaposse.jobdsl.dsl.helpers.scm.PerforcePasswordEncryptor
import javaposse.jobdsl.dsl.helpers.scm.RTCContext
import javaposse.jobdsl.dsl.helpers.scm.SvnContext

import static com.google.common.base.Preconditions.checkArgument
import static com.google.common.base.Preconditions.checkNotNull
import static com.google.common.base.Preconditions.checkState
import static javaposse.jobdsl.dsl.ContextHelper.executeInContext
import static javaposse.jobdsl.dsl.helpers.publisher.PublisherContext.validCloneWorkspaceCriteria

class ScmContext extends AbstractContext {
    private static final PerforcePasswordEncryptor PERFORCE_ENCRYPTOR = new PerforcePasswordEncryptor()

    private final boolean multiEnabled
    final List<Node> scmNodes = []
    private final List<WithXmlAction> withXmlActions

    ScmContext(boolean multiEnabled, List<WithXmlAction> withXmlActions, JobManagement jobManagement) {
        super(jobManagement)
        this.multiEnabled = multiEnabled
        this.withXmlActions = withXmlActions
    }

    /**
     * Helper method for dealing with a single scm node
     */
    Node getScmNode() {
        scmNodes[0]
    }

    private validateMulti() {
        checkState(multiEnabled || scmNodes.size() < 1, 'Outside "multiscm", only one SCM can be specified')
    }

    /**
     * Generate configuration for Mercurial.
     */
    @RequiresPlugin(id = 'mercurial')
    void hg(String url, String branch = null, Closure configure = null) {
        if (jobManagement.getPluginVersion('mercurial')?.isOlderThan(new VersionNumber('1.50.1'))) {
            jobManagement.logDeprecationWarning('support for Mercurial plugin versions older than 1.50.1')

            validateMulti()

            checkNotNull(url)

            Node scmNode = new NodeBuilder().scm(class: 'hudson.plugins.mercurial.MercurialSCM') {
                source url
                modules ''
                clean false
            }
            scmNode.appendNode('branch', branch ?: '')

            // Apply Context
            if (configure) {
                WithXmlAction action = new WithXmlAction(configure)
                action.execute(scmNode)
            }

            scmNodes << scmNode
        } else {
            hg(url) {
                delegate.branch(branch)
                delegate.configure(configure)
            }
        }
    }

    /**
     * Generate configuration for Mercurial.
     *
     * @since 1.33
     */
    @RequiresPlugin(id = 'mercurial', minimumVersion = '1.50.1')
    void hg(String url, @DslContext(HgContext) Closure hgClosure) {
        validateMulti()

        HgContext hgContext = new HgContext(jobManagement)
        executeInContext(hgClosure, hgContext)

        checkArgument(!Strings.isNullOrEmpty(url), 'url must be specified')
        checkArgument(!(hgContext.tag && hgContext.branch), 'either tag or branch should be used, not both')

        Node scmNode = new NodeBuilder().scm(class: 'hudson.plugins.mercurial.MercurialSCM') {
            source(url)
            modules(hgContext.modules.join(' '))
            revisionType(hgContext.tag ? 'TAG' : 'BRANCH')
            revision(hgContext.tag ?: hgContext.branch ?: 'default')
            clean(hgContext.clean)
            credentialsId(hgContext.credentialsId ?: '')
            disableChangeLog(hgContext.disableChangeLog)
        }
        if (hgContext.installation) {
            scmNode.appendNode('installation', hgContext.installation)
        }
        if (hgContext.subdirectory) {
            scmNode.appendNode('subdir', hgContext.subdirectory)
        }
        if (hgContext.withXmlClosure) {
            WithXmlAction action = new WithXmlAction(hgContext.withXmlClosure)
            action.execute(scmNode)
        }
        scmNodes << scmNode
    }

    /**
     * @since 1.20
     */
    @RequiresPlugin(id = 'git')
    void git(@DslContext(GitContext) Closure gitClosure) {
        validateMulti()

        GitContext gitContext = new GitContext(withXmlActions, jobManagement)
        executeInContext(gitClosure, gitContext)

        if (gitContext.branches.empty) {
            gitContext.branches << '**'
        }

        if (!jobManagement.getPluginVersion('git')?.isOlderThan(new VersionNumber('2.0.0'))) {
            if (gitContext.shallowClone || gitContext.reference || gitContext.cloneTimeout) {
                gitContext.extensions << new NodeBuilder().'hudson.plugins.git.extensions.impl.CloneOption' {
                    shallow gitContext.shallowClone
                    reference gitContext.reference
                    if (gitContext.cloneTimeout) {
                        timeout gitContext.cloneTimeout
                    }
                }
            }
        }

        Node gitNode = new NodeBuilder().scm(class: 'hudson.plugins.git.GitSCM') {
            userRemoteConfigs(gitContext.remoteConfigs)
            branches {
                gitContext.branches.each { String branch ->
                    'hudson.plugins.git.BranchSpec' {
                        name(branch)
                    }
                }
            }
            configVersion '2'
            disableSubmodules 'false'
            recursiveSubmodules 'false'
            doGenerateSubmoduleConfigurations 'false'
            authorOrCommitter 'false'
            clean gitContext.clean
            wipeOutWorkspace gitContext.wipeOutWorkspace
            pruneBranches gitContext.pruneBranches
            remotePoll gitContext.remotePoll
            ignoreNotifyCommit gitContext.ignoreNotifyCommit
            gitTool 'Default'
            if (gitContext.relativeTargetDir) {
                relativeTargetDir gitContext.relativeTargetDir
            }
            if (gitContext.localBranch) {
                localBranch gitContext.localBranch
            }
            skipTag !gitContext.createTag
            if (jobManagement.getPluginVersion('git')?.isOlderThan(new VersionNumber('2.0.0'))) {
                if (gitContext.reference) {
                    reference gitContext.reference
                }
                if (gitContext.shallowClone) {
                    useShallowClone gitContext.shallowClone
                }
            } else {
                if (gitContext.extensions) {
                    extensions gitContext.extensions
                }
            }
        }

        if (gitContext.gitBrowserContext.browser) {
            gitNode.children().add(gitContext.gitBrowserContext.browser)
        }

        if (gitContext.mergeOptions) {
            gitNode.children().add(gitContext.mergeOptions)
        }
        if (gitContext.strategyContext.buildChooser) {
            gitNode.children().add(gitContext.strategyContext.buildChooser)
        }

        // Apply Context
        if (gitContext.withXmlClosure) {
            WithXmlAction action = new WithXmlAction(gitContext.withXmlClosure)
            action.execute(gitNode)
        }
        scmNodes << gitNode
    }

    void git(String url, Closure configure = null) {
        git(url, null, configure)
    }

    void git(String url, String branch, Closure configure = null) {
        git {
            remote {
                delegate.url(url)
            }
            if (branch) {
                delegate.branch(branch)
            }
            if (configure) {
                delegate.configure(configure)
            }
            delegate.createTag()
        }
    }

    /**
     * @since 1.15
     */
    void github(String ownerAndProject, String branch = null, String protocol = 'https', Closure closure) {
        github(ownerAndProject, branch, protocol, 'github.com', closure)
    }

    /**
     * @since 1.15
     */
    void github(String ownerAndProject, String branch = null, String protocol = 'https', String host = 'github.com',
               Closure closure = null) {
        git {
            remote {
                delegate.github(ownerAndProject, protocol, host)
            }
            if (branch) {
                delegate.branch(branch)
            }
            if (closure) {
                delegate.configure(closure)
            }
        }
    }

    void svn(String svnUrl, Closure configure = null) {
        svn(svnUrl, '.', configure)
    }

    void svn(String svnUrl, String localDir, Closure configure = null) {
        checkNotNull(svnUrl)
        checkNotNull(localDir)

        svn {
            location(svnUrl) {
                directory(localDir)
            }
            delegate.configure(configure)
        }
    }

    /**
     * @since 1.30
     */
    @RequiresPlugin(id = 'subversion')
    void svn(@DslContext(SvnContext) Closure svnClosure) {
        validateMulti()

        SvnContext svnContext = new SvnContext(jobManagement)
        executeInContext(svnClosure, svnContext)

        checkState(!svnContext.locations.empty, 'One or more locations must be specified')

        Node svnNode = new NodeBuilder().scm(class: 'hudson.scm.SubversionSCM') {
            locations(svnContext.locations)
            workspaceUpdater(class: svnContext.checkoutStrategy.className)
            excludedRegions(svnContext.excludedRegions.join('\n'))
            includedRegions(svnContext.includedRegions.join('\n'))
            excludedUsers(svnContext.excludedUsers.join('\n'))
            excludedCommitMessages(svnContext.excludedCommitMessages.join('\n'))
            excludedRevprop(svnContext.excludedRevisionProperty ?: '')
        }

        if (svnContext.configureClosure) {
            WithXmlAction action = new WithXmlAction(svnContext.configureClosure)
            action.execute(svnNode)
        }

        scmNodes << svnNode
    }

    void p4(String viewspec, Closure configure = null) {
        p4(viewspec, 'rolem', '', configure)
    }

    void p4(String viewspec, String user, Closure configure = null) {
        p4(viewspec, user, '', configure)
    }

    @RequiresPlugin(id = 'perforce')
    void p4(String viewspec, String user, String password, Closure configure = null) {
        validateMulti()

        checkNotNull(viewspec)

        Node p4Node = new NodeBuilder().scm(class: 'hudson.plugins.perforce.PerforceSCM') {
            p4User user
            p4Passwd PERFORCE_ENCRYPTOR.isEncrypted(password) ? password : PERFORCE_ENCRYPTOR.encrypt(password)
            p4Port 'perforce:1666'
            p4Client 'builds-${JOB_NAME}'
            projectPath "${viewspec}"
            projectOptions 'noallwrite clobber nocompress unlocked nomodtime rmdir'
            p4Tool 'p4'
            p4SysDrive 'C:'
            p4SysRoot 'C:\\WINDOWS'
            useClientSpec 'false'
            forceSync 'false'
            alwaysForceSync 'false'
            dontUpdateServer 'false'
            disableAutoSync 'false'
            disableSyncOnly 'false'
            useOldClientName 'false'
            updateView 'true'
            dontRenameClient 'false'
            updateCounterValue 'false'
            dontUpdateClient 'false'
            exposeP4Passwd 'false'
            wipeBeforeBuild 'true'
            wipeRepoBeforeBuild 'false'
            firstChange '-1'
            slaveClientNameFormat '${basename}-${nodename}'
            lineEndValue ''
            useViewMask 'false'
            useViewMaskForPolling 'false'
            useViewMaskForSyncing 'false'
            pollOnlyOnMaster 'true'
        }

        // Apply Context
        if (configure) {
            WithXmlAction action = new WithXmlAction(configure)
            action.execute(p4Node)
        }
        scmNodes << p4Node
    }

    /**
     * @since 1.16
     */
    @RequiresPlugin(id = 'clone-workspace-scm')
    void cloneWorkspace(String parentProject, String criteriaArg = 'Any') {
        validateMulti()

        checkNotNull(parentProject)
        checkArgument(validCloneWorkspaceCriteria.contains(criteriaArg),
                "Clone Workspace Criteria needs to be one of these values: ${validCloneWorkspaceCriteria.join(',')}")

        scmNodes << new NodeBuilder().scm(class: 'hudson.plugins.cloneworkspace.CloneWorkspaceSCM') {
            parentJobName(parentProject)
            criteria(criteriaArg)
        }
    }

    /**
     * @since 1.24
     */
    @RequiresPlugin(id = 'clearcase')
    void baseClearCase(@DslContext(ClearCaseContext) Closure closure = null) {
        validateMulti()

        ClearCaseContext context = new ClearCaseContext()
        executeInContext(closure, context)

        scmNodes << new NodeBuilder().scm(class: 'hudson.plugins.clearcase.ClearCaseSCM') {
            changeset('BRANCH')
            createDynView(false)
            excludedRegions('')
            extractLoadRules(false)
            filteringOutDestroySubBranchEvent(false)
            freezeCode(false)
            loadRules(context.loadRules.join('\n'))
            loadRulesForPolling('')
            mkviewOptionalParam(context.mkviewOptionalParameter.join('\n'))
            multiSitePollBuffer(0)
            recreateView(false)
            removeViewOnRename(false)
            useDynamicView(false)
            useOtherLoadRulesForPolling(false)
            useUpdate(true)
            viewDrive('/view')
            viewName(context.viewName)
            viewPath(context.viewPath)
            branch('')
            configSpec(context.configSpec.join('\n'))
            configSpecFileName('')
            doNotUpdateConfigSpec(false)
            extractConfigSpec(false)
            label('')
            refreshConfigSpec(false)
            refreshConfigSpecCommand('')
            useTimeRule(false)
        }
    }

    /**
     * @since 1.28
     */
    @RequiresPlugin(id = 'teamconcert')
    void rtc(@DslContext(RTCContext) Closure closure) {
        validateMulti()

        RTCContext context = new RTCContext(jobManagement)
        executeInContext(closure, context)

        checkArgument(context.buildType != null, 'Either buildDefinition or buildWorkspace must be specified')

        scmNodes << new NodeBuilder().scm(class: 'com.ibm.team.build.internal.hjplugin.RTCScm') {
            overrideGlobal(context.overrideGlobal)
            timeout(context.timeout)
            if (context.overrideGlobal) {
                buildTool(context.buildTool)
                serverURI(context.serverURI)
                credentialsId(context.credentialsId)
            }
            buildType(context.buildType)
            if (context.buildType == 'buildDefinition') {
                buildDefinition(context.buildDefinition)
            } else {
                buildWorkspace(context.buildWorkspace)
            }
            avoidUsingToolkit(false)
        }
    }
}
