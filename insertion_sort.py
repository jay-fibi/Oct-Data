"""Insertion sort implementation in Python."""


def insertion_sort(arr):
    """Sort a list in ascending order using insertion sort.

    Returns a new sorted list; the input list is not modified.
    Time complexity: O(n^2) worst/average, O(n) best (already sorted).
    """
    result = list(arr)
    for i in range(1, len(result)):
        key = result[i]
        j = i - 1
        # Shift elements greater than key one position to the right
        while j >= 0 and result[j] > key:
            result[j + 1] = result[j]
            j -= 1
        result[j + 1] = key
    return result


if __name__ == "__main__":
    tests = [
        [12, 11, 13, 5, 6],
        [],
        [1],
        [5, 4, 3, 2, 1],
        [3, -1, 0, 3, 2.5],
    ]
    for t in tests:
        sorted_t = insertion_sort(t)
        assert sorted_t == sorted(t), f"Failed on {t}"
        print(f"{t} -> {sorted_t}")
    print("All tests passed.")
