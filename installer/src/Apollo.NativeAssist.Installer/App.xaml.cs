using System.Windows;
using Apollo.NativeAssist.Installer.Application;
using Apollo.NativeAssist.Installer.ViewModels;

namespace Apollo.NativeAssist.Installer;

public partial class App : System.Windows.Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ApplyLanguage("en-US");
        var recoveryStore = new RecoveryRecordStore();
        var workflow = new InstallerSessionWorkflow(new InstallerSessionFactory(), recoveryStore);
        var viewModel = new MainWindowViewModel(workflow, "en-US", ApplyLanguage);
        MainWindow = new MainWindow(viewModel);
        MainWindow.Show();
    }

    public static void ApplyLanguage(string culture)
    {
        if (culture is not ("en-US" or "ru-RU"))
        {
            throw new ArgumentOutOfRangeException(nameof(culture));
        }

        var application = Current ?? throw new InvalidOperationException("Application resources are unavailable.");
        var dictionaries = application.Resources.MergedDictionaries;
        for (var index = dictionaries.Count - 1; index >= 0; index--)
        {
            if (dictionaries[index].Source?.OriginalString.Contains("Localization/Strings.", StringComparison.Ordinal) == true)
            {
                dictionaries.RemoveAt(index);
            }
        }

        dictionaries.Add(new ResourceDictionary
        {
            Source = new Uri($"Localization/Strings.{culture}.xaml", UriKind.Relative),
        });
    }
}
