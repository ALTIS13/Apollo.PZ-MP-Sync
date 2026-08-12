using System.Windows.Input;

namespace Apollo.NativeAssist.Installer.ViewModels;

public sealed class AsyncCommand(
    Func<object?, Task> execute,
    Func<object?, bool>? canExecute = null) : ICommand
{
    private int executing;

    public event EventHandler? CanExecuteChanged;

    public bool CanExecute(object? parameter)
        => Volatile.Read(ref executing) == 0 && (canExecute?.Invoke(parameter) ?? true);

    public async void Execute(object? parameter) => await ExecuteAsync(parameter);

    public async Task ExecuteAsync(object? parameter)
    {
        if (!(canExecute?.Invoke(parameter) ?? true) || Interlocked.CompareExchange(ref executing, 1, 0) != 0)
        {
            return;
        }

        RaiseCanExecuteChanged();
        try
        {
            await execute(parameter);
        }
        finally
        {
            Interlocked.Exchange(ref executing, 0);
            RaiseCanExecuteChanged();
        }
    }

    public void RaiseCanExecuteChanged() => CanExecuteChanged?.Invoke(this, EventArgs.Empty);
}
