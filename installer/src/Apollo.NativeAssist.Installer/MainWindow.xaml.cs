using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Apollo.NativeAssist.Installer.Application;
using Apollo.NativeAssist.Installer.ViewModels;

namespace Apollo.NativeAssist.Installer;

public partial class MainWindow : Window
{
    private readonly MainWindowViewModel viewModel;
    private bool syncingSensitiveFields;
    private bool allowClose;
    private bool closePending;

    public MainWindow(MainWindowViewModel viewModel)
    {
        this.viewModel = viewModel ?? throw new ArgumentNullException(nameof(viewModel));
        InitializeComponent();
        DataContext = viewModel;
        viewModel.PropertyChanged += ViewModelPropertyChanged;
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        base.OnClosing(e);
        if (allowClose)
        {
            return;
        }

        e.Cancel = true;
        if (closePending)
        {
            return;
        }

        closePending = true;
        CompleteClose();
    }

    protected override void OnClosed(EventArgs e)
    {
        viewModel.PropertyChanged -= ViewModelPropertyChanged;
        base.OnClosed(e);
    }

    private async void CompleteClose()
    {
        try
        {
            await viewModel.ShutdownAsync();
        }
        catch (Exception error) when (!WizardExceptionPolicy.IsFatal(error))
        {
            // The view model exposes only fixed failure codes; closing must not surface raw details.
        }
        finally
        {
            closePending = false;
            allowClose = true;
            try
            {
                await Dispatcher.InvokeAsync(Close, System.Windows.Threading.DispatcherPriority.Send);
            }
            catch (Exception error) when (!WizardExceptionPolicy.IsFatal(error))
            {
                // A later close remains permitted even if the dispatcher can no longer invoke this one.
            }
        }
    }

    private void PasswordChanged(object sender, RoutedEventArgs e)
    {
        if (!syncingSensitiveFields && sender is PasswordBox box) viewModel.Password = box.Password;
    }

    private void PassphraseChanged(object sender, RoutedEventArgs e)
    {
        if (!syncingSensitiveFields && sender is PasswordBox box) viewModel.PrivateKeyPassphrase = box.Password;
    }

    private void PrivateKeyChecked(object sender, RoutedEventArgs e)
    {
        if (IsLoaded || DataContext is MainWindowViewModel) viewModel.AuthenticationMode = AuthenticationMode.PrivateKey;
    }

    private void PasswordChecked(object sender, RoutedEventArgs e)
    {
        if (IsLoaded || DataContext is MainWindowViewModel) viewModel.AuthenticationMode = AuthenticationMode.Password;
    }

    private void LanguageSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (sender is ComboBox { SelectedValue: string culture }) viewModel.SwitchLanguageCommand.Execute(culture);
    }

    private void ComposeFileLostFocus(object sender, RoutedEventArgs e)
    {
        if (sender is not TextBox box) return;
        var presenter = FindAncestor<ContentPresenter>(box);
        if (presenter is null) return;
        var index = ComposeFilesItems.ItemContainerGenerator.IndexFromContainer(presenter);
        if (index >= 0 && index < viewModel.ComposeFiles.Count) viewModel.ComposeFiles[index] = box.Text;
    }

    private void ViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is not (nameof(MainWindowViewModel.Password) or nameof(MainWindowViewModel.PrivateKeyPassphrase))) return;
        syncingSensitiveFields = true;
        try
        {
            if (e.PropertyName == nameof(MainWindowViewModel.Password) && PasswordBox.Password != viewModel.Password) PasswordBox.Password = viewModel.Password;
            if (e.PropertyName == nameof(MainWindowViewModel.PrivateKeyPassphrase) && PassphrasePasswordBox.Password != viewModel.PrivateKeyPassphrase) PassphrasePasswordBox.Password = viewModel.PrivateKeyPassphrase;
        }
        finally { syncingSensitiveFields = false; }
    }

    private static T? FindAncestor<T>(DependencyObject current) where T : DependencyObject
    {
        for (var parent = VisualTreeHelper.GetParent(current); parent is not null; parent = VisualTreeHelper.GetParent(parent))
        {
            if (parent is T typed) return typed;
        }
        return null;
    }
}
