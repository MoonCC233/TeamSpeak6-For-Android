// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Immutable;

namespace TeamSpeak9.Core.Model;

/// <summary>
/// Puts sibling channels into display order.
/// </summary>
/// <remarks>
/// <para>
/// TeamSpeak does not store a sort index. <c>channel_order</c> is a <b>backwards pointer</b>: it
/// holds the id of the channel that comes <i>before</i> this one among its siblings, and 0 means
/// "first". So the siblings of a channel form a singly linked list, and the only correct way to
/// order them is to walk that list. Sorting by the numeric value of <c>channel_order</c> gives a
/// wrong order as soon as channels have been moved around, because the values are ids, not ranks.
/// </para>
/// <para>
/// The list can be broken in practice: a channel may point at a sibling we never received (an
/// unsubscribed or filtered channel), and a buggy or hostile server could produce a cycle. Both
/// are handled by falling back to appending the leftovers rather than dropping them or looping
/// forever, so a malformed list degrades the order but never loses a channel and never hangs.
/// </para>
/// <para>See <c>TSLib/Full/Book/Book.cs</c> (<c>ChannelOrderRemove</c> / <c>ChannelOrderInsert</c>).</para>
/// </remarks>
public static class ChannelOrdering
{
    /// <summary>
    /// Orders one sibling group by following its <c>channel_order</c> chain.
    /// </summary>
    /// <param name="siblings">Channels that share a parent. Not modified.</param>
    /// <param name="idOf">Reads a channel's own id.</param>
    /// <param name="orderOf">Reads a channel's <c>channel_order</c>, i.e. its predecessor's id.</param>
    /// <returns>
    /// The channels in display order. Channels whose predecessor is missing, duplicated or part of
    /// a cycle are appended after the well-formed chain, in input order.
    /// </returns>
    public static ImmutableArray<T> SortSiblings<T>(
        IReadOnlyList<T> siblings,
        Func<T, ulong> idOf,
        Func<T, ulong> orderOf)
    {
        ArgumentNullException.ThrowIfNull(siblings);
        ArgumentNullException.ThrowIfNull(idOf);
        ArgumentNullException.ThrowIfNull(orderOf);

        if (siblings.Count == 0)
            return [];

        if (siblings.Count == 1)
            return [siblings[0]];

        // Index by predecessor. Duplicates would mean two channels claim the same slot, which the
        // server should never send; keeping the first and treating the rest as leftovers avoids
        // silently dropping one of them.
        var byPredecessor = new Dictionary<ulong, T>(siblings.Count);
        var duplicates = new List<T>();
        foreach (var sibling in siblings)
        {
            if (!byPredecessor.TryAdd(orderOf(sibling), sibling))
                duplicates.Add(sibling);
        }

        var result = ImmutableArray.CreateBuilder<T>(siblings.Count);
        var placed = new HashSet<ulong>(siblings.Count);

        // 0 marks the head of the chain.
        ulong cursor = 0;
        while (byPredecessor.TryGetValue(cursor, out var next))
        {
            ulong id = idOf(next);

            // A channel pointing (directly or transitively) at itself would loop forever.
            if (!placed.Add(id))
                break;

            result.Add(next);
            cursor = id;
        }

        if (result.Count == siblings.Count)
            return result.MoveToImmutable();

        // Whatever the walk did not reach: channels whose predecessor is not in this group at all,
        // members of a cycle, and the duplicates set aside above. Input order keeps this stable.
        foreach (var sibling in siblings)
        {
            if (placed.Add(idOf(sibling)))
                result.Add(sibling);
        }

        foreach (var duplicate in duplicates)
        {
            if (!result.Contains(duplicate))
                result.Add(duplicate);
        }

        return result.ToImmutable();
    }

    /// <summary>
    /// Orders channel members the way the official client does: talk power descending, then name.
    /// </summary>
    /// <remarks>
    /// Clients have no explicit order on the wire. The official client groups by talk power so
    /// moderators and priority speakers float to the top of a moderated channel, then sorts
    /// alphabetically. Comparison is culture aware and case insensitive to match what users expect
    /// from a nickname list.
    /// </remarks>
    public static ImmutableArray<ChannelMember> SortMembers(IReadOnlyList<ChannelMember> members)
    {
        ArgumentNullException.ThrowIfNull(members);

        if (members.Count <= 1)
            return members.Count == 0 ? [] : [members[0]];

        var sorted = members.ToArray();
        Array.Sort(sorted, static (a, b) =>
        {
            int byPower = b.TalkPower.CompareTo(a.TalkPower);
            if (byPower != 0)
                return byPower;

            int byName = string.Compare(a.Name, b.Name, StringComparison.CurrentCultureIgnoreCase);
            return byName != 0 ? byName : a.ClientId.CompareTo(b.ClientId);
        });

        return [.. sorted];
    }
}
