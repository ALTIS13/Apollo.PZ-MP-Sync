using System.Collections.ObjectModel;
using System.Globalization;
using System.Text.RegularExpressions;

namespace Apollo.NativeAssist.Installer.Core;

public sealed record RedactedEvent(
    long Sequence,
    string Name,
    IReadOnlyDictionary<string, string> Fields);

public enum LogScalarKind
{
    StructuredToken,
    Boolean,
    Integer,
    Number,
    Sha256,
}

public sealed record LogFieldPolicy(string Name, LogScalarKind Kind);

public sealed partial class RedactedLog
{
    private static readonly string[] ForbiddenFieldFragments =
    [
        "password",
        "token",
        "secret",
        "key",
        "credential",
        "rcon",
        "command",
    ];

    private readonly Dictionary<string, LogScalarKind> fieldPolicies;
    private readonly string[] sensitiveValues;
    private readonly List<RedactedEvent> events = [];

    public RedactedLog(
        IEnumerable<LogFieldPolicy> allowedFields,
        IEnumerable<string> sensitiveValues)
    {
        ArgumentNullException.ThrowIfNull(allowedFields);
        ArgumentNullException.ThrowIfNull(sensitiveValues);

        fieldPolicies = new Dictionary<string, LogScalarKind>(StringComparer.Ordinal);
        foreach (var policy in allowedFields)
        {
            ArgumentNullException.ThrowIfNull(policy);
            ValidateFieldName(policy.Name);
            if (!fieldPolicies.TryAdd(policy.Name, policy.Kind))
            {
                throw new ArgumentException("Duplicate field name.", nameof(allowedFields));
            }
        }

        this.sensitiveValues = sensitiveValues.Select(value =>
        {
            if (string.IsNullOrEmpty(value))
            {
                throw new ArgumentException("Sensitive values must not be empty.", nameof(sensitiveValues));
            }

            return value;
        }).ToArray();
    }

    public IReadOnlyList<RedactedEvent> Events => events.AsReadOnly();

    public void Append(string eventName, IReadOnlyDictionary<string, object?> fields)
    {
        if (eventName is null || !StructuredEventName().IsMatch(eventName))
        {
            throw new ArgumentException("Event name must be structured.", nameof(eventName));
        }

        ArgumentNullException.ThrowIfNull(fields);

        var sanitized = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var (key, value) in fields)
        {
            ValidateFieldName(key);
            if (!fieldPolicies.TryGetValue(key, out var kind))
            {
                throw new ArgumentException("Event field is not allowlisted.", nameof(fields));
            }

            sanitized.Add(key, FormatScalar(kind, value));
        }

        events.Add(new RedactedEvent(
            events.Count + 1L,
            eventName,
            new ReadOnlyDictionary<string, string>(sanitized)));
    }

    private static void ValidateFieldName(string? fieldName)
    {
        if (fieldName is null || !StructuredFieldName().IsMatch(fieldName))
        {
            throw new ArgumentException("Field name must be structured.", nameof(fieldName));
        }

        foreach (var fragment in ForbiddenFieldFragments)
        {
            if (fieldName.Contains(fragment, StringComparison.OrdinalIgnoreCase))
            {
                throw new ArgumentException("Sensitive or command field names are forbidden.", nameof(fieldName));
            }
        }
    }

    private string FormatScalar(LogScalarKind kind, object? value)
    {
        if (value is string text && MustRedact(text))
        {
            return "[REDACTED]";
        }

        return kind switch
        {
            LogScalarKind.StructuredToken when value is string token && StructuredTokenValue().IsMatch(token)
                => token,
            LogScalarKind.Boolean when value is bool boolean
                => boolean ? "true" : "false",
            LogScalarKind.Integer when IsInteger(value)
                => ((IFormattable)value!).ToString(null, CultureInfo.InvariantCulture),
            LogScalarKind.Number when TryFormatNumber(value, out var number)
                => number,
            LogScalarKind.Sha256 when value is string digest && Sha256Value().IsMatch(digest)
                => digest,
            _ => throw new ArgumentException("Value does not match the field's scalar policy.", nameof(value)),
        };
    }

    private bool MustRedact(string value)
    {
        if (RecognizedCredential().IsMatch(value))
        {
            return true;
        }

        return sensitiveValues.Any(secret => value.Contains(secret, StringComparison.Ordinal));
    }

    private static bool IsInteger(object? value)
        => value is byte or sbyte or short or ushort or int or uint or long or ulong;

    private static bool TryFormatNumber(object? value, out string formatted)
    {
        switch (value)
        {
            case byte or sbyte or short or ushort or int or uint or long or ulong:
                formatted = ((IFormattable)value).ToString(null, CultureInfo.InvariantCulture);
                return true;
            case float number when float.IsFinite(number):
                formatted = number.ToString("R", CultureInfo.InvariantCulture);
                return true;
            case double number when double.IsFinite(number):
                formatted = number.ToString("R", CultureInfo.InvariantCulture);
                return true;
            case decimal number:
                formatted = number.ToString(CultureInfo.InvariantCulture);
                return true;
            default:
                formatted = string.Empty;
                return false;
        }
    }

    [GeneratedRegex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$", RegexOptions.CultureInvariant)]
    private static partial Regex StructuredEventName();

    [GeneratedRegex("^[A-Za-z][A-Za-z0-9._-]*$", RegexOptions.CultureInvariant)]
    private static partial Regex StructuredFieldName();

    [GeneratedRegex("^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}$", RegexOptions.CultureInvariant)]
    private static partial Regex StructuredTokenValue();

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256Value();

    [GeneratedRegex("(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}|[A-Za-z][A-Za-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@)", RegexOptions.CultureInvariant)]
    private static partial Regex RecognizedCredential();
}
