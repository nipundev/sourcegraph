package com.sourcegraph.cody;

import static java.awt.event.KeyEvent.VK_ENTER;
import static javax.swing.KeyStroke.getKeyStroke;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.IconUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.sourcegraph.cody.agent.CodyAgent;
import com.sourcegraph.cody.chat.AssistantMessageWithSettingsButton;
import com.sourcegraph.cody.chat.Chat;
import com.sourcegraph.cody.chat.ChatMessage;
import com.sourcegraph.cody.chat.ChatScrollPane;
import com.sourcegraph.cody.chat.ChatUIConstants;
import com.sourcegraph.cody.chat.ContextFilesMessage;
import com.sourcegraph.cody.chat.MessagePanel;
import com.sourcegraph.cody.chat.Transcript;
import com.sourcegraph.cody.context.ContextMessage;
import com.sourcegraph.cody.context.EmbeddingStatusView;
import com.sourcegraph.cody.editor.EditorUtil;
import com.sourcegraph.cody.localapp.LocalAppManager;
import com.sourcegraph.cody.recipes.ExplainCodeDetailedAction;
import com.sourcegraph.cody.recipes.ExplainCodeHighLevelAction;
import com.sourcegraph.cody.recipes.FindCodeSmellsAction;
import com.sourcegraph.cody.recipes.GenerateDocStringAction;
import com.sourcegraph.cody.recipes.GenerateUnitTestAction;
import com.sourcegraph.cody.recipes.ImproveVariableNamesAction;
import com.sourcegraph.cody.recipes.RecipeRunner;
import com.sourcegraph.cody.recipes.SummarizeRecentChangesRecipe;
import com.sourcegraph.cody.recipes.TranslateToLanguageAction;
import com.sourcegraph.cody.ui.AutoGrowingTextArea;
import com.sourcegraph.cody.ui.HtmlViewer;
import com.sourcegraph.cody.vscode.CancellationToken;
import com.sourcegraph.config.ConfigUtil;
import com.sourcegraph.config.SettingsComponent;
import com.sourcegraph.config.SettingsComponent.InstanceType;
import com.sourcegraph.telemetry.GraphQlLogger;
import com.sourcegraph.vcs.RepoUtil;
import java.awt.*;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ButtonUI;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

public class CodyToolWindowContent implements UpdatableChat {
  public static Logger logger = Logger.getInstance(CodyToolWindowContent.class);
  private static final int CHAT_TAB_INDEX = 0;
  private static final int RECIPES_TAB_INDEX = 1;
  private final @NotNull CardLayout allContentLayout = new CardLayout();
  private final @NotNull JPanel allContentPanel = new JPanel(allContentLayout);
  private final @NotNull JBTabbedPane tabbedPane = new JBTabbedPane();
  private final @NotNull JPanel messagesPanel = new JPanel();
  private final @NotNull JBTextArea promptInput;
  private final @NotNull JButton sendButton;
  private final @NotNull Project project;
  private final JButton stopGeneratingButton =
      new JButton("Stop generating", IconUtil.desaturate(AllIcons.Actions.Suspend));
  private @NotNull volatile CancellationToken cancellationToken = new CancellationToken();
  private final JPanel stopGeneratingButtonPanel;
  private @NotNull Transcript transcript = new Transcript();
  private boolean isChatVisible = false;

  public CodyToolWindowContent(@NotNull Project project) {
    this.project = project;
    // Tabs
    @NotNull JPanel contentPanel = new JPanel();
    tabbedPane.insertTab("Chat", null, contentPanel, null, CHAT_TAB_INDEX);
    @NotNull JPanel recipesPanel = new JPanel(new GridLayout(0, 1));
    recipesPanel.setLayout(new BoxLayout(recipesPanel, BoxLayout.Y_AXIS));
    tabbedPane.insertTab("Recipes", null, recipesPanel, null, RECIPES_TAB_INDEX);

    // Recipes panel
    RecipeRunner recipeRunner = new RecipeRunner(this.project, this);
    JButton explainCodeDetailedButton = createRecipeButton("Explain selected code (detailed)");
    explainCodeDetailedButton.addActionListener(
        e -> new ExplainCodeDetailedAction().executeRecipeWithPromptProvider(this, project));
    JButton explainCodeHighLevelButton = createRecipeButton("Explain selected code (high level)");
    explainCodeHighLevelButton.addActionListener(
        e -> new ExplainCodeHighLevelAction().executeRecipeWithPromptProvider(this, project));
    JButton generateUnitTestButton = createRecipeButton("Generate a unit test");
    generateUnitTestButton.addActionListener(
        e -> new GenerateUnitTestAction().executeRecipeWithPromptProvider(this, project));
    JButton generateDocstringButton = createRecipeButton("Generate a docstring");
    generateDocstringButton.addActionListener(
        e -> new GenerateDocStringAction().executeRecipeWithPromptProvider(this, project));
    JButton improveVariableNamesButton = createRecipeButton("Improve variable names");
    improveVariableNamesButton.addActionListener(
        e -> new ImproveVariableNamesAction().executeRecipeWithPromptProvider(this, project));
    JButton translateToLanguageButton = createRecipeButton("Translate to different language");
    translateToLanguageButton.addActionListener(
        e -> new TranslateToLanguageAction().executeAction(project));
    JButton gitHistoryButton = createRecipeButton("Summarize recent code changes");
    gitHistoryButton.addActionListener(
        e ->
            new SummarizeRecentChangesRecipe(project, this, recipeRunner).summarizeRecentChanges());
    JButton findCodeSmellsButton = createRecipeButton("Smell code");
    findCodeSmellsButton.addActionListener(
        e -> new FindCodeSmellsAction().executeRecipeWithPromptProvider(this, project));
    recipesPanel.add(explainCodeDetailedButton);
    recipesPanel.add(explainCodeHighLevelButton);
    recipesPanel.add(generateUnitTestButton);
    recipesPanel.add(generateDocstringButton);
    recipesPanel.add(improveVariableNamesButton);
    recipesPanel.add(translateToLanguageButton);
    recipesPanel.add(gitHistoryButton);
    recipesPanel.add(findCodeSmellsButton);
    enableAutoUpdateAvailabilityOfSummarizeRecentCodeChangesRecipe(project, gitHistoryButton);

    // Chat panel
    messagesPanel.setLayout(new VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, true));
    ChatScrollPane chatPanel = new ChatScrollPane(messagesPanel);

    // Controls panel
    JPanel controlsPanel = new JPanel();
    controlsPanel.setLayout(new BorderLayout());
    controlsPanel.setBorder(new EmptyBorder(JBUI.insets(0, 14, 14, 14)));
    JPanel promptPanel = new JPanel(new BorderLayout());
    sendButton = createSendButton(this.project);
    AutoGrowingTextArea autoGrowingTextArea = new AutoGrowingTextArea(3, 9, promptPanel);
    promptInput = autoGrowingTextArea.getTextArea();
    /* Submit on enter */
    KeyboardShortcut JUST_ENTER = new KeyboardShortcut(getKeyStroke(VK_ENTER, 0), null);
    ShortcutSet DEFAULT_SUBMIT_ACTION_SHORTCUT = new CustomShortcutSet(JUST_ENTER);
    AnAction sendMessageAction =
        new DumbAwareAction() {
          @Override
          public void actionPerformed(@NotNull AnActionEvent e) {
            sendMessage(project);
          }
        };
    sendMessageAction.registerCustomShortcutSet(DEFAULT_SUBMIT_ACTION_SHORTCUT, promptInput);

    promptPanel.add(autoGrowingTextArea.getScrollPane(), BorderLayout.CENTER);
    promptPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

    stopGeneratingButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 5));
    stopGeneratingButtonPanel.setPreferredSize(
        new Dimension(Short.MAX_VALUE, stopGeneratingButton.getPreferredSize().height + 10));
    stopGeneratingButton.addActionListener(
        e -> {
          cancellationToken.abort();
          stopGeneratingButton.setVisible(false);
          sendButton.setEnabled(true);
        });
    stopGeneratingButton.setVisible(false);
    stopGeneratingButtonPanel.add(stopGeneratingButton);
    stopGeneratingButtonPanel.setOpaque(false);
    controlsPanel.add(promptPanel, BorderLayout.NORTH);
    controlsPanel.add(sendButton, BorderLayout.EAST);
    JPanel lowerPanel = new JPanel(new BorderLayout());
    Color borderColor = ColorUtil.brighter(UIUtil.getPanelBackground(), 3);
    Border topBorder = BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor);
    lowerPanel.setBorder(topBorder);
    lowerPanel.setLayout(new BoxLayout(lowerPanel, BoxLayout.Y_AXIS));
    lowerPanel.add(stopGeneratingButtonPanel);
    lowerPanel.add(controlsPanel);

    EmbeddingStatusView embeddingStatusView = new EmbeddingStatusView(project);
    embeddingStatusView.setBorder(topBorder);
    lowerPanel.add(embeddingStatusView);

    // Main content panel
    contentPanel.setLayout(new BorderLayout(0, 0));
    contentPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

    contentPanel.add(chatPanel, BorderLayout.CENTER);
    contentPanel.add(lowerPanel, BorderLayout.SOUTH);

    tabbedPane.addChangeListener(e -> this.focusPromptInput());

    JPanel appNotInstalledPanel = createAppNotInstalledPanel();
    JPanel appNotRunningPanel = createAppNotRunningPanel();
    allContentPanel.add(tabbedPane, "tabbedPane");
    allContentPanel.add(appNotInstalledPanel, "appNotInstalledPanel");
    allContentPanel.add(appNotRunningPanel, "appNotRunningPanel");
    allContentLayout.show(allContentPanel, "appNotInstalledPanel");
    updateVisibilityOfContentPanels();
    // Add welcome message
    addWelcomeMessage();
  }

  public static CodyToolWindowContent getInstance(@NotNull Project project) {
    return project.getService(CodyToolWindowContent.class);
  }

  private static void enableAutoUpdateAvailabilityOfSummarizeRecentCodeChangesRecipe(
      @NotNull Project project, @NotNull JButton gitHistoryButton) {
    updateAvailabilityOfTheSummarizeRecentCodeChangesRecipe(project, gitHistoryButton);
    MessageBusConnection messageBusConnection = project.getMessageBus().connect();
    messageBusConnection.subscribe(
        ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
        () -> updateAvailabilityOfTheSummarizeRecentCodeChangesRecipe(project, gitHistoryButton));
  }

  private static void updateAvailabilityOfTheSummarizeRecentCodeChangesRecipe(
      @NotNull Project project, @NotNull JButton gitHistoryButton) {
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              VirtualFile currentFile = EditorUtil.getCurrentFile(project);

              ApplicationManager.getApplication()
                  .executeOnPooledThread(
                      () -> {
                        boolean noVersionControlSystem =
                            RepoUtil.findRepositoryName(project, currentFile) == null;
                        if (noVersionControlSystem) {
                          gitHistoryButton.setEnabled(false);
                          gitHistoryButton.setToolTipText("No version control system found");
                        } else {
                          gitHistoryButton.setEnabled(true);
                          gitHistoryButton.setToolTipText(null);
                        }
                      });
            });
  }

  private void updateVisibilityOfContentPanels() {
    if (LocalAppManager.isPlatformSupported()
        && ConfigUtil.getInstanceType(project) == SettingsComponent.InstanceType.LOCAL_APP) {
      if (!LocalAppManager.isLocalAppInstalled()) {
        allContentLayout.show(allContentPanel, "appNotInstalledPanel");
        isChatVisible = false;
      } else if (!LocalAppManager.isLocalAppRunning()) {
        allContentLayout.show(allContentPanel, "appNotRunningPanel");
        isChatVisible = false;
      } else {
        allContentLayout.show(allContentPanel, "tabbedPane");
        isChatVisible = true;
      }
    } else {
      allContentLayout.show(allContentPanel, "tabbedPane");
      isChatVisible = true;
    }
  }

  @NotNull
  private JPanel createAppNotInstalledPanel() {
    JEditorPane jEditorPane = HtmlViewer.createHtmlViewer(UIUtil.getPanelBackground());
    jEditorPane.setText(
        "<html><body><h2>Get Started</h2>"
            + "<p>This plugin requires the Cody desktop app to enable context fetching for your private code."
            + " Download and run the Cody desktop app to Configure your local code graph.</p><"
            + "/body></html>");
    JButton downloadCodyAppButton = createMainButton("Download Cody App");
    downloadCodyAppButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, Boolean.TRUE);
    downloadCodyAppButton.addActionListener(
        e -> {
          BrowserUtil.browse("https://about.sourcegraph.com/app");
          updateVisibilityOfContentPanels();
        });
    return new CodyOnboardingPanel(project, jEditorPane, downloadCodyAppButton);
  }

  @NotNull
  private JPanel createAppNotRunningPanel() {

    JEditorPane jEditorPane = HtmlViewer.createHtmlViewer(UIUtil.getPanelBackground());
    jEditorPane.setText(
        "<html><body><h2>Cody App Not Running</h2>"
            + "<p>This plugin requires the Cody desktop app to enable context fetching for your private code.</p><"
            + "/body></html>");
    JButton runCodyAppButton = createMainButton("Open Cody App");
    runCodyAppButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, Boolean.TRUE);
    runCodyAppButton.addActionListener(
        e -> {
          LocalAppManager.runLocalApp();
          updateVisibilityOfContentPanels();
        });
    return new CodyOnboardingPanel(project, jEditorPane, runCodyAppButton);
  }

  @NotNull
  private JButton createRecipeButton(@NotNull String text) {
    JButton button = new JButton(text);
    button.setAlignmentX(Component.CENTER_ALIGNMENT);
    button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
    ButtonUI buttonUI = (ButtonUI) DarculaButtonUI.createUI(button);
    button.setUI(buttonUI);
    return button;
  }

  @NotNull
  private static JButton createMainButton(@NotNull String text) {
    JButton button = new JButton(text);
    button.setMaximumSize(new Dimension(Short.MAX_VALUE, button.getPreferredSize().height));
    button.setAlignmentX(Component.CENTER_ALIGNMENT);
    ButtonUI buttonUI = (ButtonUI) DarculaButtonUI.createUI(button);
    button.setUI(buttonUI);
    return button;
  }

  private void addWelcomeMessage() {
    String accessToken = ConfigUtil.getProjectAccessToken(project);
    String welcomeText =
        "Hello! I'm Cody. I can write code and answer questions for you. See [Cody documentation](https://docs.sourcegraph.com/cody) for help and tips.";
    addMessageToChat(ChatMessage.createAssistantMessage(welcomeText));
    if (StringUtils.isEmpty(accessToken)) {
      String noAccessTokenText =
          "<p>It looks like you don't have Sourcegraph Access Token configured.</p>"
              + "<p>See our <a href=\"https://docs.sourcegraph.com/cli/how-tos/creating_an_access_token\">user docs</a> how to create one and configure it in the settings to use Cody.</p>";
      AssistantMessageWithSettingsButton assistantMessageWithSettingsButton =
          new AssistantMessageWithSettingsButton(noAccessTokenText);
      var messageContentPanel = new JPanel(new BorderLayout());
      messageContentPanel.add(assistantMessageWithSettingsButton);
      ApplicationManager.getApplication()
          .invokeLater(() -> addComponentToChat(messageContentPanel));
    }
  }

  @NotNull
  private JButton createSendButton(@NotNull Project project) {
    JButton sendButton = new JButton("Send");
    sendButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, Boolean.TRUE);
    ButtonUI buttonUI = (ButtonUI) DarculaButtonUI.createUI(sendButton);
    sendButton.setUI(buttonUI);
    sendButton.addActionListener(
        e -> {
          GraphQlLogger.logCodyEvent(this.project, "recipe:chat-question", "clicked");
          sendMessage(project);
        });
    return sendButton;
  }

  public synchronized void addMessageToChat(@NotNull ChatMessage message) {
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              // Bubble panel
              MessagePanel messagePanel =
                  new MessagePanel(
                      message,
                      project,
                      messagesPanel,
                      ChatUIConstants.ASSISTANT_MESSAGE_GRADIENT_WIDTH);
              addComponentToChat(messagePanel);
            });
  }

  private void addComponentToChat(@NotNull JPanel messageContent) {

    var wrapperPanel = new JPanel();
    wrapperPanel.setLayout(new VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false));
    // Chat message
    wrapperPanel.add(messageContent, VerticalFlowLayout.TOP);
    messagesPanel.add(wrapperPanel);
    messagesPanel.revalidate();
    messagesPanel.repaint();
  }

  @Override
  public void activateChatTab() {
    this.tabbedPane.setSelectedIndex(CHAT_TAB_INDEX);
  }

  @Override
  public void respondToMessage(@NotNull ChatMessage message, @NotNull String responsePrefix) {
    activateChatTab();
    sendMessage(this.project, message, responsePrefix);
  }

  @Override
  public void respondToErrorFromServer(@NotNull String errorMessage) {
    if (errorMessage.equals("Connection refused")) {
      this.addMessageToChat(
          ChatMessage.createAssistantMessage(
              "I'm sorry, I can't connect to the server. Please make sure that the server is running and try again."));
    } else if (errorMessage.startsWith("Got error response 401")) {
      addMessageWithInformationAboutInvalidAccessToken();
    } else {
      this.addMessageToChat(
          ChatMessage.createAssistantMessage(
              "I'm sorry, something went wrong. Please try again. The error message I got was: \""
                  + errorMessage
                  + "\"."));
    }
  }

  private void addMessageWithInformationAboutInvalidAccessToken() {
    String invalidAccessTokenText =
        "<p>It looks like your Sourcegraph Access Token is invalid or not configured.</p>"
            + "<p>See our <a href=\"https://docs.sourcegraph.com/cli/how-tos/creating_an_access_token\">user docs</a> how to create one and configure it in the settings to use Cody.</p>";
    AssistantMessageWithSettingsButton assistantMessageWithSettingsButton =
        new AssistantMessageWithSettingsButton(invalidAccessTokenText);
    var messageContentPanel = new JPanel(new BorderLayout());
    messageContentPanel.add(assistantMessageWithSettingsButton);
    ApplicationManager.getApplication()
        .invokeLater(() -> this.addComponentToChat(messageContentPanel));
  }

  public synchronized void updateLastMessage(@NotNull ChatMessage message) {
    ApplicationManager.getApplication()
        .invokeLater(
            () ->
                Optional.of(messagesPanel)
                    .filter(mp -> mp.getComponentCount() > 0)
                    .map(mp -> mp.getComponent(mp.getComponentCount() - 1))
                    .filter(component -> component instanceof JPanel)
                    .map(component -> (JPanel) component)
                    .map(lastWrapperPanel -> lastWrapperPanel.getComponent(0))
                    .filter(component -> component instanceof MessagePanel)
                    .map(component -> (MessagePanel) component)
                    .ifPresent(
                        lastMessage -> {
                          transcript.addAssistantResponse(message);
                          lastMessage.updateContentWith(message);
                        }));
  }

  private void startMessageProcessing() {
    cancellationToken.abort();
    cancellationToken = new CancellationToken();
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              stopGeneratingButton.setVisible(true);
              sendButton.setEnabled(false);
            });
  }

  @Override
  public void finishMessageProcessing() {
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              stopGeneratingButton.setVisible(false);
              sendButton.setEnabled(true);
            });
  }

  @Override
  public void resetConversation() {
    transcript = new Transcript();
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              cancellationToken.abort();
              stopGeneratingButton.setVisible(false);
              sendButton.setEnabled(true);
              messagesPanel.removeAll();
              addWelcomeMessage();
              messagesPanel.revalidate();
              messagesPanel.repaint();
            });
  }

  @Override
  public void refreshPanelsVisibility() {
    this.updateVisibilityOfContentPanels();
  }

  @Override
  public boolean isChatVisible() {
    return this.isChatVisible;
  }

  private void sendMessage(@NotNull Project project) {
    String text = promptInput.getText();
    promptInput.setText("");
    sendMessage(project, ChatMessage.createHumanMessage(text, text), "");
  }

  private void sendMessage(
      @NotNull Project project, @NotNull ChatMessage message, @NotNull String responsePrefix) {
    if (!sendButton.isEnabled()) {
      return;
    }
    startMessageProcessing();
    // Build message
    String truncatedPrompt =
        TruncationUtils.truncateText(message.prompt(), TruncationUtils.MAX_HUMAN_INPUT_TOKENS);
    ChatMessage humanMessage =
        ChatMessage.createHumanMessage(truncatedPrompt, message.getDisplayText());
    addMessageToChat(humanMessage);
    VirtualFile currentFile = EditorUtil.getCurrentFile(project);
    // This cannot run on EDT (Event Dispatch Thread) because it may block for a long time.
    // Also, if we did the back-end call in the main thread and then waited, we wouldn't see the
    // messages streamed back to us.
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            () -> {
              String accessToken = ConfigUtil.getProjectAccessToken(project);
              Chat chat = new Chat();
              try {
                chat.sendMessageViaAgent(
                    CodyAgent.getClient(project),
                    CodyAgent.getInitializedServer(project),
                    humanMessage,
                    this,
                    cancellationToken);
              } catch (Exception e) {
                logger.warn("Error sending message '" + humanMessage + "' to chat", e);
              }
              GraphQlLogger.logCodyEvent(this.project, "recipe:chat-question", "executed");
            });
  }

  @Override
  public void displayUsedContext(@NotNull List<ContextMessage> contextMessages) {
    // Use context
    if (contextMessages.size() == 0) {
      InstanceType instanceType = ConfigUtil.getInstanceType(project);

      String report = "I found no context for your request.";
      String ask =
          instanceType == InstanceType.ENTERPRISE
              ? "Please ensure this repository is added to your Sourcegraph Enterprise instance and that your access token and custom request headers are set up correctly."
              : (instanceType == InstanceType.LOCAL_APP
                  ? "Please ensure this repository is configured in Cody App."
                  : (instanceType == InstanceType.DOTCOM
                      ? "As your current server setting is Sourcegraph.com, please ensure this repository is public and indexed on Sourcegraph.com and that your access token is valid."
                      : ""));
      String resolution = "I will try to answer without context.";
      this.addMessageToChat(
          ChatMessage.createAssistantMessage(report + " " + ask + " " + resolution));
    } else {

      ContextFilesMessage contextFilesMessage = new ContextFilesMessage(contextMessages);
      var messageContentPanel = new JPanel(new BorderLayout());
      messageContentPanel.add(contextFilesMessage);
      this.addComponentToChat(messageContentPanel);
    }
  }

  public @NotNull JComponent getContentPanel() {
    return allContentPanel;
  }

  public void focusPromptInput() {
    if (tabbedPane.getSelectedIndex() == CHAT_TAB_INDEX) {
      promptInput.requestFocusInWindow();
      int textLength = promptInput.getDocument().getLength();
      promptInput.setCaretPosition(textLength);
    }
  }

  public JComponent getPreferredFocusableComponent() {
    return promptInput;
  }
}