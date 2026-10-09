import java.util.Arrays;

public class MergeSort {

    public static void mergeSort(int[] arr) {
        if (arr == null || arr.length < 2) return;
        int[] temp = new int[arr.length];
        sort(arr, temp, 0, arr.length - 1);
    }

    private static void sort(int[] arr, int[] temp, int left, int right) {
        if (left >= right) return;
        int mid = left + (right - left) / 2;
        sort(arr, temp, left, mid);
        sort(arr, temp, mid + 1, right);
        merge(arr, temp, left, mid, right);
    }

    private static void merge(int[] arr, int[] temp, int left, int mid, int right) {
        int i = left, j = mid + 1, k = left;
        while (i <= mid && j <= right) {
            temp[k++] = (arr[i] <= arr[j]) ? arr[i++] : arr[j++];
        }
        while (i <= mid) temp[k++] = arr[i++];
        while (j <= right) temp[k++] = arr[j++];
        for (k = left; k <= right; k++) arr[k] = temp[k];
    }

    public static void main(String[] args) {
        int[] data = {38, 27, 43, 3, 9, 82, 10};
        System.out.println("Original: " + Arrays.toString(data));
        mergeSort(data);
        System.out.println("Sorted:   " + Arrays.toString(data));
    }
}
